"""Request optional host frame metadata only for an active local statistics session."""
import argparse
from pathlib import Path


def change(path, replacements):
    text = path.read_text(encoding="utf-8")
    for before, after in replacements:
        if after in text:
            continue
        if text.count(before) != 1:
            raise RuntimeError(f"Host frame trace anchor missing or ambiguous: {path}: {before[:80]}")
        text = text.replace(before, after, 1)
    path.write_text(text, encoding="utf-8", newline="\n")


def windows(root):
    change(root / "app/backend/nvhttp.cpp", [
        ("    QString rhInitialDisplay;", "    const auto rhFrameTraceQuery=RhStatistics::launchQuery();\n    QString rhInitialDisplay;"),
        ("RhDisplayLaunch::query(rhInitialDisplay)+LiGetLaunchUrlQueryParameters()", "rhFrameTraceQuery+RhDisplayLaunch::query(rhInitialDisplay)+LiGetLaunchUrlQueryParameters()"),
        ('    const auto rhAck=getXmlString(response,"RHInitialDisplay");', '''    RhStatistics::event("host_frame_trace_request",-1,{{"requested",!rhFrameTraceQuery.isEmpty()},{"status",RhHostFrameTrace::acknowledge(!rhFrameTraceQuery.isEmpty(),getXmlString(response,"RHFrameTrace"))}});
    const auto rhAck=getXmlString(response,"RHInitialDisplay");'''),
    ])


def android(root):
    change(root / "app/src/main/java/com/limelight/nvstream/http/NvHTTP.java", [
        ('        String rhDisplayQuery=rhInitialDisplay==null?"":rhInitialDisplay.launchQuery();', '''        String rhTraceQuery=com.limelight.rhremote.RhStatistics.launchQuery();
        String rhDisplayQuery=rhInitialDisplay==null?"":rhInitialDisplay.launchQuery();'''),
        ('rhDisplayQuery + MoonBridge.getLaunchUrlQueryParameters()', 'rhTraceQuery + rhDisplayQuery + MoonBridge.getLaunchUrlQueryParameters()'),
        ('            if(rhInitialDisplay!=null)rhInitialDisplay.acknowledgeInitial(getXmlString(xmlStr,"RHInitialDisplay",false));', '''            com.limelight.rhremote.RhStatistics.event("host_frame_trace_request",-1,com.limelight.rhremote.RhActions.obj("requested",!rhTraceQuery.isEmpty(),"status",com.limelight.rhremote.RhHostFrameTrace.acknowledge(!rhTraceQuery.isEmpty(),getXmlString(xmlStr,"RHFrameTrace",false))));
            if(rhInitialDisplay!=null)rhInitialDisplay.acknowledgeInitial(getXmlString(xmlStr,"RHInitialDisplay",false));'''),
    ])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", choices=("windows", "android"))
    parser.add_argument("source", type=Path)
    arguments = parser.parse_args()
    (windows if arguments.platform == "windows" else android)(arguments.source)
