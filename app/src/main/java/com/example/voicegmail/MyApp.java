package com.example.voicegmail;

import android.app.Application;
import android.util.Log;
import java.io.FileOutputStream;

public class MyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (FileOutputStream fos = openFileOutput("last_crash.txt", MODE_PRIVATE)) {
                fos.write(Log.getStackTraceString(e).getBytes());
            } catch (Exception ignored) {}
            System.exit(2);
        });
    }
}
