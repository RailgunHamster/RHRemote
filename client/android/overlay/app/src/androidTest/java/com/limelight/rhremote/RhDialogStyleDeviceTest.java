package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Own dialog render/layout matrix, with dummy actions and no host or WebDAV operations. */
@RunWith(AndroidJUnit4.class)
public final class RhDialogStyleDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;File folder;boolean apply,strict;
    void main(Runnable action){instrument.runOnMainSync(action);instrument.waitForIdleSync();}
    int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    JSONObject rect(View view,View decor)throws Exception{int[] p=new int[2],o=new int[2];view.getLocationInWindow(p);decor.getLocationInWindow(o);Rect visible=new Rect();boolean shown=view.getGlobalVisibleRect(visible);return RhActions.obj("x",p[0]-o[0],"y",p[1]-o[1],"width",view.getWidth(),"height",view.getHeight(),"fullVisible",shown&&visible.width()==view.getWidth()&&visible.height()==view.getHeight());}
    void capture(AlertDialog dialog,String name)throws Exception{
        Bitmap[] image={null};JSONObject[] result={null};
        main(()->{try{View decor=dialog.getWindow().getDecorView();JSONArray keys=new JSONArray(),ancestors=new JSONArray();List<Rect> occupied=new ArrayList<>();
            for(int type:new int[]{AlertDialog.BUTTON_NEUTRAL,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_POSITIVE}){Button button=dialog.getButton(type);if(button==null||button.getVisibility()!=View.VISIBLE)continue;JSONObject bounds=rect(button,decor);bounds.put("type",type).put("label",button.getText()).put("enabled",button.isEnabled()).put("lines",button.getLineCount());keys.put(bounds);
                if(strict){assertTrue(button.getText().toString(),bounds.getBoolean("fullVisible"));assertTrue("Touch target too short",button.getHeight()>=dp(48));assertEquals("Dialog action label wrapped",1,button.getLineCount());Rect r=new Rect(bounds.getInt("x"),bounds.getInt("y"),bounds.getInt("x")+bounds.getInt("width"),bounds.getInt("y")+bounds.getInt("height"));for(Rect previous:occupied)assertFalse("Actions overlap",Rect.intersects(previous,r));occupied.add(r);android.graphics.drawable.Drawable current=button.getBackground().getCurrent();if(current instanceof android.graphics.drawable.GradientDrawable){android.content.res.ColorStateList fill=((android.graphics.drawable.GradientDrawable)current).getColor();assertNotNull(fill);double contrast=androidx.core.graphics.ColorUtils.calculateContrast(button.getCurrentTextColor(),fill.getDefaultColor());bounds.put("contrast",contrast);assertTrue("Action text contrast too low: "+contrast,contrast>=4.5);}}
                if(ancestors.length()==0){View p=button;for(int i=0;i<5&&p!=null;i++){ancestors.put(RhActions.obj("class",p.getClass().getName(),"rect",rect(p,decor),"focused",p.isFocused(),"hasFocus",p.hasFocus(),"defaultFocusHighlight",p.getDefaultFocusHighlightEnabled(),"background",p.getBackground()==null?"none":p.getBackground().getClass().getName(),"color",p.getBackground() instanceof ColorDrawable?((ColorDrawable)p.getBackground()).getColor():JSONObject.NULL));p=p.getParent() instanceof View?(View)p.getParent():null;}}
            }
            result[0]=RhActions.obj("name",name,"width",decor.getWidth(),"height",decor.getHeight(),"fontScale",activity.getResources().getConfiguration().fontScale,"styled",apply,"strict",strict,"actions",keys,"ancestors",ancestors);image[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image[0]));
        }catch(Exception e){throw new RuntimeException(e);}});
        try{try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(image[0].compress(Bitmap.CompressFormat.PNG,100,out));}
            Bitmap pixels=Bitmap.createBitmap(image[0].getWidth(),image[0].getHeight(),Bitmap.Config.ARGB_8888);int[] copied={-1};CountDownLatch done=new CountDownLatch(1);
            try{main(()->PixelCopy.request(dialog.getWindow(),pixels,code->{copied[0]=code;done.countDown();},new android.os.Handler(android.os.Looper.getMainLooper())));assertTrue(done.await(3,TimeUnit.SECONDS));result[0].put("windowPixelCopyResult",copied[0]);assertEquals("Own dialog window PixelCopy",PixelCopy.SUCCESS,copied[0]);try(OutputStream out=new FileOutputStream(new File(folder,name+"-window.png"))){assertTrue(pixels.compress(Bitmap.CompressFormat.PNG,100,out));}}finally{pixels.recycle();}
            RhShared.write(new File(folder,name+".json"),result[0]);
        }finally{image[0].recycle();}
    }
    @Test public void nativeDialogActionsKeepTheirCallbacksAndFitTheCurrentViewport()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("dialogRun","");org.junit.Assume.assumeFalse("Explicit owned run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));apply=InstrumentationRegistry.getArguments().getString("dialogApply","false").equals("true");strict=InstrumentationRegistry.getArguments().getString("dialogStrict","false").equals("true");Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());RhSessionFlowDeviceTest holder=new RhSessionFlowDeviceTest();holder.folder=folder;
        try(RhSessionStateSnapshot snapshot=new RhSessionStateSnapshot(holder,run)){
            activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();
            for(boolean dark:new boolean[]{false,true})for(String kind:new String[]{"save","remove","close","list"}){
                int[] called={0};AlertDialog[] dialog={null};main(()->{RhShared.theme(context,dark);AlertDialog.Builder b=new AlertDialog.Builder(activity,RhUiStyle.dialogTheme(activity));
                    if(kind.equals("save"))b.setTitle("保存更改？").setMessage("连接设置有未保存的更改。").setNeutralButton("继续编辑",(d,w)->called[0]++).setNegativeButton("放弃更改",(d,w)->called[0]++).setPositiveButton("保存并继续",(d,w)->called[0]++);
                    else if(kind.equals("remove"))b.setTitle("移除同步设置？").setMessage("仅移除此设备保存的 WebDAV 地址和登录信息，并关闭自动同步。本机远程设备和服务器上的配置文件保留。").setNegativeButton("取消",(d,w)->called[0]++).setPositiveButton("移除",(d,w)->called[0]++);
                    else if(kind.equals("close"))b.setTitle("操作失败").setMessage("网络连接暂时不可用，请检查连接后重试。").setNegativeButton("关闭",(d,w)->called[0]++);
                    else{String[] labels=new String[24];for(int i=0;i<labels.length;i++)labels[i]="远程快捷键 "+(i+1);b.setTitle("远程快捷键").setItems(labels,(d,w)->called[0]++).setNegativeButton("关闭",(d,w)->called[0]++);}
                    dialog[0]=b.create();dialog[0].show();if(apply)try{java.lang.reflect.Method method=RhUiStyle.class.getDeclaredMethod("dialog",AlertDialog.class,boolean.class);method.setAccessible(true);method.invoke(null,dialog[0],kind.equals("remove"));}catch(Exception e){throw new RuntimeException(e);}
                });
                try{Thread.sleep(120);instrument.waitForIdleSync();capture(dialog[0],kind+"-"+(dark?"dark":"light"));
                    if(kind.equals("list")){ListView list=dialog[0].getListView();main(()->list.setSelection(list.getCount()-1));Thread.sleep(120);instrument.waitForIdleSync();main(()->{int last=list.getCount()-1;View row=list.getChildAt(last-list.getFirstVisiblePosition());assertNotNull("Last option is unreachable",row);Rect visible=new Rect();assertTrue(row.getGlobalVisibleRect(visible));assertEquals(row.getHeight(),visible.height());});capture(dialog[0],kind+"-"+(dark?"dark":"light")+"-end");main(()->{int last=list.getCount()-1;assertTrue(list.performItemClick(list.getChildAt(last-list.getFirstVisiblePosition()),last,list.getAdapter().getItemId(last)));});}
                    else main(()->{Button button=dialog[0].getButton(kind.equals("save")||kind.equals("remove")?AlertDialog.BUTTON_POSITIVE:AlertDialog.BUTTON_NEGATIVE);assertTrue(button.isEnabled());button.performClick();});assertEquals("Original action callback replaced",1,called[0]);assertFalse(dialog[0].isShowing());}finally{main(dialog[0]::dismiss);}
            }
            RhShared.write(new File(folder,"result.json"),RhActions.obj("passed",true,"styled",apply,"strict",strict,"cases",8,"actualNativeButtons",true,"callbacksPreserved",true,"lastListOptionReached",true,"hostOperations",false,"webdavOperations",false,"systemInputInjected",false));
        }
    }
}
