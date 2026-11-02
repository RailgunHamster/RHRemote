package com.limelight.rhremote;

import android.app.Activity;
import android.text.Spanned;
import io.noties.markwon.core.spans.StrongEmphasisSpan;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import com.limelight.R;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,qualifiers="mdpi",shadows={RhUpdatesTest.Controls.class})
public class RhReleaseNotesTest{
    @Test public void markdown_is_rendered_as_native_spans(){Activity activity=Robolectric.buildActivity(Activity.class).setup().get();RhReleaseNotes notes=new RhReleaseNotes(activity);Spanned value=notes.render("# 版本\n\n## 修复\n\n- **清晰** *文字* 与 `代码`\n\n```txt\na < b\n```\n");assertFalse(value.toString().contains("# "));assertFalse(value.toString().contains("**"));assertFalse(value.toString().contains("```"));assertTrue(value.toString().contains("清晰"));assertTrue(value.toString().contains("a < b"));assertTrue("Strong emphasis must retain its native span",value.getSpans(0,value.length(),StrongEmphasisSpan.class).length>0);}
    private HttpURLConnection response(String url,int code,byte[] bytes)throws Exception{return new HttpURLConnection(new URL(url)){@Override public int getResponseCode(){return code;}@Override public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}@Override public void connect(){}@Override public void disconnect(){}@Override public boolean usingProxy(){return false;}};}
    private void await(RhReleaseHistory history)throws Exception{long end=System.currentTimeMillis()+4000;while(history.state().optBoolean("busy")&&System.currentTimeMillis()<end)Thread.sleep(10);assertFalse(history.state().optBoolean("busy"));}
    @Test public void history_is_lazy_and_can_collapse_while_loading_and_fall_back()throws Exception{
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();RhReleaseHistory history=new RhReleaseHistory(activity,()->{});AtomicInteger requests=new AtomicInteger();byte[] bytes;try(InputStream in=activity.getAssets().open("rhremote-release-history.json")){bytes=RhReleaseHistory.read(in);}history.connections=url->{int count=requests.incrementAndGet();try{return response(url,count==1?503:200,bytes);}catch(Exception e){throw new IOException(e);}};
        assertEquals(0,requests.get());assertFalse(history.state().getBoolean("expanded"));history.execute("updates.history.toggle",RhActions.obj());history.execute("updates.history.toggle",RhActions.obj());await(history);assertFalse(history.state().getBoolean("expanded"));assertEquals(2,requests.get());assertEquals("github",history.state().getString("provider"));history.execute("updates.history.toggle",RhActions.obj());history.execute("updates.history.entry.toggle",RhActions.obj("version","0.2.17"));assertEquals("0.2.17",history.state().getJSONArray("expandedVersions").getString(0));assertEquals(2,requests.get());
    }
    @Test public void malformed_or_duplicate_history_is_rejected()throws Exception{String item="{\"version\":\"0.2.17\",\"publishedAt\":\"\",\"notes\":\"ok\"}";try{RhReleaseHistory.parse(("{\"schemaVersion\":1,\"releases\":["+item+","+item+"]}").getBytes(StandardCharsets.UTF_8));fail();}catch(IOException expected){}}
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void real_update_page_renders_markdown_and_history_is_folded_below_it()throws Exception{
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);RhHome home=new RhHome(activity,new RhHome.Actions(){public void connect(com.limelight.nvstream.http.ComputerDetails d){}public void add(String address){}public void advanced(){}public void pair(com.limelight.nvstream.http.ComputerDetails d,String pin){}public void cancelPair(){}public void refresh(){}});
        RhUpdates updates=ReflectionHelpers.getField(home,"updates");ReflectionHelpers.setField(updates,"latest",RhActions.obj("version","0.2.18","notes","# RHRemote 0.2.18\n\n## 改进\n\n- **Markdown** 更新说明\n- 历史记录默认收起\n\n```txt\n代码示例\n```"));
        RhUpdateWork work=ReflectionHelpers.getField(home,"updateWork");ReflectionHelpers.setField(work.history,"loaded",true);
        home.perform("app.settings.open",RhActions.obj());View root=activity.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(900,View.MeasureSpec.EXACTLY));root.layout(0,0,840,900);
        LinearLayout history=ReflectionHelpers.getField(home,"historyBody");assertEquals(View.GONE,history.getVisibility());TextView text=ReflectionHelpers.getField(home,"updateNotes");assertTrue(text.getText() instanceof Spanned);assertFalse(text.getText().toString().contains("# RHRemote"));
        home.perform("updates.history.toggle",RhActions.obj());assertEquals(View.VISIBLE,history.getVisibility());home.perform("updates.history.entry.toggle",RhActions.obj("version","0.2.17"));LinearLayout entries=ReflectionHelpers.getField(home,"historyItems");assertTrue(entries.getChildCount()>1);
        java.io.File dir=new java.io.File("build/reports/rhremote-markdown");assertTrue(dir.isDirectory()||dir.mkdirs());for(boolean open:new boolean[]{false,true}){if(!open)home.perform("updates.history.toggle",RhActions.obj());else home.perform("updates.history.toggle",RhActions.obj());root.measure(View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(900,View.MeasureSpec.EXACTLY));root.layout(0,0,840,900);Bitmap image=Bitmap.createBitmap(840,900,Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));try(FileOutputStream out=new FileOutputStream(new File(dir,open?"history-open.png":"history-closed.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}home.dispose();
    }
}
