package com.limelight.rhremote;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RhStatisticsExportTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    final String run="1792902497278-1861e2cd-835";
    File root,directory;
    @Before public void setup()throws Exception {root=temporary.newFolder("statistics");directory=new File(root,run);assertTrue(directory.mkdir());write("manifest.json","{\"schema\":\"rhremote.statistics/v1\"}");}
    void write(String name,String text)throws Exception {Files.write(new File(directory,name).toPath(),text.getBytes(StandardCharsets.UTF_8));}
    void rejected(String id,String file)throws Exception {try(RhStatisticsExport.Export ignored=RhStatisticsExport.open(root,id,file)){fail("Accepted unavailable or unowned trace");}catch(IOException expected){}}
    @Test public void exportsExactBytesAndStrongDigestAfterTheWriterFooter()throws Exception {
        String text="{\"event\":\"session_end\"}\n{\"event\":\"trace_end\",\"lost_events\":0}\n";write("native.jsonl",text);
        try(RhStatisticsExport.Export export=RhStatisticsExport.open(root,run,"native.jsonl")){
            byte[] bytes=new byte[(int)export.length];assertEquals(bytes.length,export.input.read(bytes));
            assertEquals(text,new String(bytes,StandardCharsets.UTF_8));assertEquals(-1,export.input.read());
            StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format("%02x",b&255));
            assertEquals(sha.toString(),export.sha256);
        }
        try(RhStatisticsExport.Export manifest=RhStatisticsExport.open(root,run,"manifest.json")){assertTrue(manifest.length>0);}
    }
    @Test public void rejectsActiveTruncatedAndMissingWritersIncludingManifest()throws Exception {
        rejected(run,"manifest.json");
        for(String text:new String[]{"","{\"event\":\"render_submit\"}\n","{\"event\":\"trace_end\"}","{\"event\":\"trace_end\"}\n{\"event\":\"render_submit\"}\n"}){
            write("native.jsonl",text);rejected(run,"native.jsonl");rejected(run,"manifest.json");
        }
    }
    @Test public void rejectsPathsAndNonTelemetryFiles()throws Exception {
        write("native.jsonl","{\"event\":\"trace_end\"}\n");
        for(String id:new String[]{"..","../"+run,run+"/..",run+"%2f..",run+"\\..","1792902497278-deadbeef-000"})rejected(id,"native.jsonl");
        for(String file:new String[]{"../rhremote-control-token.txt","rhremote-control-token.txt","keep","manifest.json/../native.jsonl"})rejected(run,file);
    }
    @Test public void cleanupAcknowledgementRequiresTheExactExportedDigest()throws Exception {
        write("native.jsonl","{\"event\":\"trace_end\"}\n");String sha;
        try(RhStatisticsExport.Export export=RhStatisticsExport.open(root,run,"native.jsonl")){sha=export.sha256;}
        try{RhStatisticsExport.complete(root,run,new String(new char[64]).replace('\0','0'));fail("Wrong digest acknowledged");}catch(IOException expected){}
        assertFalse(new File(directory,"analysis-complete.json").exists());
        RhStatisticsExport.complete(root,run,sha);
        assertTrue(new File(directory,"analysis-complete.json").isFile());assertTrue(new File(directory,"native.jsonl").isFile());
    }
}
