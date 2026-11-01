package com.limelight.rhremote;

/** Owned View host, included in Debug or an explicitly isolated Release UI build only. */
public final class RhUiFixtureActivity extends android.app.Activity {
    private static volatile float fixtureFontScale;
    static void fontScale(float value){if(value!=0f&&value!=1f&&value!=2f)throw new IllegalArgumentException("Fixture font scale must be 0, 1 or 2");fixtureFontScale=value;}
    @Override protected void attachBaseContext(android.content.Context context){
        float scale=fixtureFontScale;if(scale>0){android.content.res.Configuration configuration=new android.content.res.Configuration(context.getResources().getConfiguration());configuration.fontScale=scale;context=context.createConfigurationContext(configuration);}super.attachBaseContext(context);
    }
}
