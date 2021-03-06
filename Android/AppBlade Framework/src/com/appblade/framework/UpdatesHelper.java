package com.appblade.framework;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.json.JSONException;
import org.json.JSONObject;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

public class UpdatesHelper {

	private static final int NotificationNewVersion = 0;
	private static final int NotificationNewVersionDownloading = 1;

	public static void processUpdate(Activity context, JSONObject update) {
		
		PackageInfo pkg = AppBlade.getPackageInfo();
		String permission = Manifest.permission.WRITE_EXTERNAL_STORAGE;
		if(SystemUtils.hasPermission(pkg, permission)) {
			Log.d(AppBlade.LogTag, "UpdatesHelper.processUpdate - permission to write to sd, downloading...");
			downloadUpdate(context, update);
		}
		else {
			Log.d(AppBlade.LogTag, "UpdatesHelper.processUpdate - no permission to write to sd, notifying...");
			notifyUpdate(context, update);
		}
	}

	private static void downloadUpdate(Activity context, JSONObject update) {
		File dir = getRootDirectory();
		File file = new File(dir, "install.apk");
		
		long expectedFileSize = 0;
		long totalBytesRead = 0;
		boolean savedSuccessfully = false;
		String url = null;
		
		InputStream inputStream = null;
		BufferedInputStream bufferedInputStream = null;
		FileOutputStream fileOutput = null;
		BufferedOutputStream bufferedOutput = null;
		
		try
		{
			url = update.getString("url");			
			expectedFileSize = HttpUtils.getHeaderAsLong(url, HttpUtils.HeaderContentLength);
			Log.d(AppBlade.LogTag, String.format("Downloading %d bytes from %s", expectedFileSize, url));
			
			HttpGet request = new HttpGet();
			request.setURI(new URI(url));
			HttpClient client = HttpClientProvider.newInstance("Android");
			HttpResponse response = client.execute(request);
			if(response != null) {
				
				if(file.exists())
					file.delete();
				file.createNewFile();
				notifyDownloading(context);
				
				inputStream = response.getEntity().getContent();
				bufferedInputStream = new BufferedInputStream(inputStream);
				
		    	fileOutput = new FileOutputStream(file);
		    	bufferedOutput = new BufferedOutputStream(fileOutput);
		    	
		        byte[] buffer = new byte[1024 * 16];
		        totalBytesRead = 0;
		    	
		    	while(true)
		    	{
		    		synchronized (buffer)
		    		{
			    		int bytesRead = bufferedInputStream.read(buffer);
			    		if(bytesRead > 0) {
				    		bufferedOutput.write(buffer, 0, bytesRead);
			    			totalBytesRead += bytesRead;
			    		}
			    		else {
			    			// end of file...
			    			savedSuccessfully = true;
			    			break;
			    		}
		    		}
		    	}
			}
		}
		catch(JSONException ex) { }
		catch(URISyntaxException ex) { }
		catch(ClientProtocolException ex) { }
		catch(IOException ex) { }
		finally
		{
			NotificationManager notificationManager =
					(NotificationManager) context.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
			notificationManager.cancel(NotificationNewVersionDownloading);
			
			IOUtils.safeClose(bufferedInputStream);
			IOUtils.safeClose(bufferedOutput);
			
			Log.d(AppBlade.LogTag, String.format("%d bytes downloaded from %s", totalBytesRead, url));
			
			if(expectedFileSize > 0 && expectedFileSize == totalBytesRead)
				savedSuccessfully = true;
			
			if(savedSuccessfully) {
				Log.d(AppBlade.LogTag, String.format("Download succeeded, opening file at %s", file.getAbsolutePath()));
				open(context, file);
			}
			else {
				Log.d(AppBlade.LogTag, "Download failed, fall back to notifying and let the browser handle it");
				notifyUpdate(context, update);
				KillSwitch.kill(context);
			}
		}
	}

	private static void open(final Activity context, final File file) {
		context.runOnUiThread(new Runnable() {
			
			public void run() {
				AlertDialog.Builder builder = new AlertDialog.Builder(context);
				builder.setMessage("A new version has been downloaded, click OK to install");
				builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
					
					public void onClick(DialogInterface dialog, int which) {
						Intent intent = new Intent(Intent.ACTION_VIEW);
						intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
						context.startActivity(intent);
						KillSwitch.kill(context);
					}
				});
				builder.setOnCancelListener(new OnCancelListener() {
					
					public void onCancel(DialogInterface dialog) {
						KillSwitch.kill(context);
					}
				});
				builder.create().show();
			}
		});
	}

	private static File getRootDirectory() {
		String rootDir = ".appblade";
		String path = String.format("%s/%s",
				Environment.getExternalStorageDirectory().getAbsolutePath(),
				rootDir);
		File dir = new File(path);
		dir.mkdirs();
		return dir;
	}
	
	private static void notifyDownloading(Activity context) {
		Intent blank = new Intent();
		PendingIntent contentIntent = PendingIntent.getBroadcast(context, 0, blank, 0);
		
		String message = "Downloading update...";
		NotificationManager notificationManager =
				(NotificationManager) context.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
		Notification notification = new Notification(R.drawable.notification_icon, message, System.currentTimeMillis());
		notification.setLatestEventInfo(context.getApplicationContext(), "Update", message, contentIntent);
		notificationManager.notify(NotificationNewVersionDownloading, notification);
	}

	private static void notifyUpdate(Activity context, JSONObject update) {
		Log.d(AppBlade.LogTag, "KillSwitch.processUpdate");
		try
		{
			String url = update.getString("url");
			String message = update.getString("message");
			
			if(context != null) {
				Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
				PendingIntent contentIntent = PendingIntent.getActivity(context.getApplicationContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
				
				NotificationManager notificationManager =
						(NotificationManager) context.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
				Notification notification = new Notification(R.drawable.notification_icon, message, System.currentTimeMillis());
				notification.setLatestEventInfo(context.getApplicationContext(), "Update", message, contentIntent);
				notificationManager.notify(NotificationNewVersion, notification);
			}
				
		}
		catch(JSONException ex) {}
	}
}
