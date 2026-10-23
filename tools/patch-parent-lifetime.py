"""Stop managed native sessions cleanly when their original Windows UI exits."""
import argparse
from pathlib import Path


def patch(root):
    path=root/"app/streaming/session.cpp"
    text=path.read_text(encoding="utf-8")
    text=text.replace('if(RhRuntime::get().ownerExited.load())','if(RhRuntime::get().ownerExited.load()||RhRuntime::get().localDisconnect.load())')
    text=text.replace('RhRuntime::get().sessionActive=true;','RhRuntime::beginSession();')
    for before,after in [
        ('void Session::clStageStarting(int stage)\n{', 'void Session::clStageStarting(int stage)\n{\n    if(RhRuntime::get().ownerExited.load()||RhRuntime::get().localDisconnect.load())LiInterruptConnection();'),
        ('    QString rtspSessionUrl;', '    if(RhRuntime::get().ownerExited.load()||RhRuntime::get().localDisconnect.load()){RhStatistics::event("owner_launch_aborted",-1,{{"stage","before_http_launch"}});return false;}\n    QString rtspSessionUrl;'),
        ('    RhStatistics::event("connection_begin");', '    if(RhRuntime::get().ownerExited.load()||RhRuntime::get().localDisconnect.load()){RhStatistics::event("owner_launch_aborted",-1,{{"stage","before_protocol_start"}});return false;}\n    RhStatistics::event("connection_begin");'),
        ('    RhStatistics::event("connection_ready");', '    if(RhRuntime::get().ownerExited.load()||RhRuntime::get().localDisconnect.load())return false;\n    RhStatistics::event("connection_ready");'),
        ('void Session::execInternal()\n{', 'void Session::execInternal()\n{\n    RhRuntime::beginSession();\n    if(RhRuntime::get().ownerExited.load()||RhRuntime::get().localDisconnect.load()){emit sessionFinished(0);emit readyForDeletion();return;}'),
        ('    for (;;) {\n#ifdef Q_OS_WIN32\n        if (runnerStopEvent', '    for (;;) {\n        if(RhRuntime::get().ownerExited.load()||RhRuntime::get().localDisconnect.load())goto DispatchDeferredCleanup;\n#ifdef Q_OS_WIN32\n        if (runnerStopEvent'),
    ]:
        if after in text:continue
        if text.count(before)!=1:raise RuntimeError("Parent lifetime adaptation anchor missing or ambiguous: "+before[:70])
        text=text.replace(before,after,1)
    path.write_text(text,encoding="utf-8",newline="\n")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source",type=Path)
    patch(parser.parse_args().source)
