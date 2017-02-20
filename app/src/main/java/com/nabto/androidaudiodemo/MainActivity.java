package com.nabto.androidaudiodemo;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;

import com.nabto.api.*;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        NabtoApi api = new NabtoApi(new NabtoAndroidAssetManager(this));

        // Start Nabto
        api.startup();

        // Login as guest
        Session session = api.openSession("guest", "123456");
        if (session.getStatus() == NabtoStatus.OK) {

            // Make a Nabto request to a device
            String url = "nabto://demo.nabto.net/wind_speed.json?";
            UrlResult result = api.fetchUrl(url, session);
            if (result.getStatus() == NabtoStatus.OK) {

                // Get the response
                String response = new String(result.getResult());
                Log.v("nabto-test", response);
            }
        }

        // Stop Nabto
        api.closeSession(session);
        api.shutdown();
    }
}
