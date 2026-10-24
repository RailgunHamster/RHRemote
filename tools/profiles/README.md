# Encoder wait recordings

These are the exact profiles used by the QSVjoint05 investigation. They select
events; the bounded diagnostic runner owns the recording lifetime, independent
stop task, service-window restoration and private output files.
Git attributes preserve their measured bytes, including the CPU profile's
existing UTF-8 BOM and CRLF line endings.

| Profile | WPR name | Purpose |
| --- | --- | --- |
| `encoder-resource-waits.wprp` | `RhResource` | Device/context and allocation lifetimes, explicit local/global allocation mapping, backing-store locks and paging requests. Start before encoder initialization. |
| `encoder-queue-waits.wprp` | `RhQueue` | Command queue and DMA scheduler boundaries. Record during the same actual frames as CPU waits. |
| `encoder-cpu-waits.wprp` | `RhEncoder` | Context switches, ready events, sampled stacks and disk/DPC metadata. FileIO name events and audio/network payload keywords are not enabled; process/module metadata remains private. |

The resource and queue profiles use WPR's
[event ID filters](https://learn.microsoft.com/en-us/windows-hardware/test/wpt/eventfilters).
The live smoke test verified both the presence of required events and the
absence of unselected IDs, with zero lost events/buffers or metadata errors.
Selecting only the Resource keyword previously retained millions of page-table
operations; the event filter excludes that unrelated volume.

Use distinct named WPR instances. In the measured run, resource recording had a
240-second independent stop task; CPU/queue recording had 90-second tasks and
were normally stopped after about 40 seconds. Check current recordings before
starting and only stop the instance owned by the current run. The profiles do
not implement those controls themselves.

Keep the original ETLs and SHA256 while needed. Match the actual image identities,
process lifetimes, native QPC clocks and explicit resource birth/death records
before joining traces. A resource can be released in a System or other process
context; filtering destruction by the original owner loses identity boundaries.
Kernel and user stack fragments must match their exact timestamp, CPU and payload
process/thread before being combined. A current event context alone does not
identify the cause of a wake.

DMA scheduler spans are not GPU instruction execution times. Private driver
buffer sizes do not identify their purpose. Recording overhead and valid source
playback are separate from stable frame-rate acceptance. Raw ETLs, process
metadata, binaries and PDBs stay outside the public repository.
