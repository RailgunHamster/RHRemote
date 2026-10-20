package com.limelight.rhremote;
import android.content.Context;
import com.limelight.binding.audio.AndroidAudioRenderer;
import java.util.concurrent.atomic.AtomicLong;

/** Client playback gain only. No OS volume changes and no audio samples are stored. */
public final class RhAudioRenderer extends AndroidAudioRenderer {
    private volatile boolean enabled;private volatile int volume;private final AtomicLong packets=new AtomicLong();
    public RhAudioRenderer(Context c,boolean effects,RhProfile profile){super(c,effects);setPlayback(profile.audioEnabled,profile.volume);}
    public void setPlayback(boolean enabled,int volume){if(volume<0||volume>100)throw new IllegalArgumentException("volume must be 0..100");this.enabled=enabled;this.volume=volume;}
    public boolean enabled(){return enabled;}public int volume(){return volume;}public long packets(){return packets.get();}
    public static short[] gain(short[] samples,int volume){if(volume<0||volume>100)throw new IllegalArgumentException("volume must be 0..100");if(volume==100)return samples;short[] scaled=new short[samples.length];for(int i=0;i<samples.length;i++)scaled[i]=(short)((int)samples[i]*volume/100);return scaled;}
    @Override public void playDecodedAudio(short[] samples){packets.incrementAndGet();if(enabled&&volume>0)super.playDecodedAudio(gain(samples,volume));}
}
