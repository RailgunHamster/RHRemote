package com.limelight.rhremote;

import android.content.Context;
import android.text.Spanned;
import android.widget.TextView;
import io.noties.markwon.Markwon;
import java.util.WeakHashMap;

/** Native TextView spans; no WebView or image-download plugin. */
final class RhReleaseNotes{
    private final Markwon markdown;private final WeakHashMap<TextView,String> rendered=new WeakHashMap<>();
    RhReleaseNotes(Context context){this(context,url->{});}
    RhReleaseNotes(Context context,java.util.function.Consumer<String> open){boolean dark=RhShared.dark(context);markdown=Markwon.builder(context.getApplicationContext()).usePlugin(new io.noties.markwon.AbstractMarkwonPlugin(){@Override public void configureConfiguration(io.noties.markwon.MarkwonConfiguration.Builder builder){builder.linkResolver((view,url)->open.accept(url));}@Override public void configureTheme(io.noties.markwon.core.MarkwonTheme.Builder builder){builder.linkColor(dark?0xff7f9fff:0xff5274e8).codeTextColor(dark?0xffe1e8f6:0xff34415b).codeBlockTextColor(dark?0xffe1e8f6:0xff34415b).codeBackgroundColor(dark?0xff303c53:0xffedf0f7).codeBlockBackgroundColor(dark?0xff151b29:0xfff6f7fa).blockQuoteColor(dark?0xff303c53:0xffe5eaf4).headingBreakColor(dark?0xff303c53:0xffe5eaf4);}}).build();}
    Spanned render(String source){return markdown.toMarkdown(source.length()>65536?source.substring(0,65536):source);}
    void set(TextView view,String source){if(source.equals(rendered.get(view)))return;markdown.setParsedMarkdown(view,render(source));rendered.put(view,source);}
}
