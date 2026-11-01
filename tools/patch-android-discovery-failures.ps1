param([Parameter(Mandatory=$true)][string]$Text)
$ErrorActionPreference='Stop';$Text=$Text.Replace("`r`n","`n")
function Replace-DiscoveryAnchor([string]$Source,[string]$Before,[string]$After){
    $Before=$Before.Replace("`r`n","`n");$After=$After.Replace("`r`n","`n")
    if(([regex]::Matches($Source,[regex]::Escape($Before))).Count-ne1){throw ('Discovery failure anchor must match once: '+$Before.Substring(0,[Math]::Min(80,$Before.Length)))}
    return $Source.Replace($Before,$After)
}
$start=$Text.IndexOf('    private ComputerDetails tryPollIp(');$end=$Text.IndexOf('    private static class ParallelPollTuple',$start)
if($start-lt0-or$end-le$start){throw 'Discovery request block missing'}
$block=$Text.Substring($start,$end-$start)
$block=Replace-DiscoveryAnchor $block @'
            ComputerDetails rhPollSource=new ComputerDetails(details);
            String rhPollIntent=com.limelight.rhremote.RhDiscoveryObservation.begin(getApplicationContext(),rhPollSource);
'@ ''
$block=Replace-DiscoveryAnchor $block @'
    private ComputerDetails tryPollIp(ComputerDetails details, ComputerDetails.AddressTuple address) {
        try {
'@ @'
    private ComputerDetails tryPollIp(ComputerDetails details, ComputerDetails.AddressTuple address) {
        return tryPollIp(details,address,null);
    }
    private ComputerDetails tryPollIp(ComputerDetails details, ComputerDetails.AddressTuple address, com.limelight.rhremote.RhDiscoveryFailures rhFailures) {
        try {
            ComputerDetails rhPollSource=rhFailures==null?new ComputerDetails(details):rhFailures.source();
            String rhPollIntent=rhFailures==null?com.limelight.rhremote.RhDiscoveryObservation.begin(getApplicationContext(),rhPollSource):rhFailures.intent();
'@
foreach($field in @('state','activeAddress','httpsPort','uuid')){$block=$block.Replace(('details.'+$field),('rhPollSource.'+$field))}
$block=Replace-DiscoveryAnchor $block '                LimeLog.severe("Polling returned no UUID!");' '                if(rhFailures!=null)rhFailures.failed(address,"invalid_response","");
                LimeLog.severe("Polling returned no UUID!");'
$block=Replace-DiscoveryAnchor $block '                LimeLog.info("Polling returned the wrong PC!");' '                if(rhFailures!=null)rhFailures.failed(address,"identity_mismatch",newDetails.uuid);
                LimeLog.info("Polling returned the wrong PC!");'
$block=Replace-DiscoveryAnchor $block '        } catch (XmlPullParserException e) {' '        } catch (XmlPullParserException e) {
            if(rhFailures!=null)rhFailures.failed(address,e);'
$block=Replace-DiscoveryAnchor $block '        } catch (IOException e) {' '        } catch (IOException e) {
            if(rhFailures!=null)rhFailures.failed(address,e);'
$Text=$Text.Remove($start,$end-$start).Insert($start,$block)
$Text=Replace-DiscoveryAnchor $Text '    private void startParallelPollThread(ParallelPollTuple tuple, HashSet<ComputerDetails.AddressTuple> uniqueAddresses) {' '    private void startParallelPollThread(ParallelPollTuple tuple, HashSet<ComputerDetails.AddressTuple> uniqueAddresses, com.limelight.rhremote.RhDiscoveryFailures rhFailures) {'
$Text=Replace-DiscoveryAnchor $Text '        tuple.pollingThread = new Thread() {' '        if(rhFailures!=null)rhFailures.register(tuple.address);
        tuple.pollingThread = new Thread() {'
$Text=Replace-DiscoveryAnchor $Text '                ComputerDetails details = tryPollIp(tuple.existingDetails, tuple.address);' '                ComputerDetails details = tryPollIp(tuple.existingDetails, tuple.address, rhFailures);'
$Text=Replace-DiscoveryAnchor $Text '    private ComputerDetails parallelPollPc(ComputerDetails details) throws InterruptedException {' '    private ComputerDetails parallelPollPc(ComputerDetails details) throws InterruptedException {
        com.limelight.rhremote.RhDiscoveryFailures rhFailures=com.limelight.rhremote.RhDiscoveryFailures.begin(getApplicationContext(),details);'
foreach($tuple in @('localInfo','manualInfo','remoteInfo','ipv6Info','tuple')){
    $Text=Replace-DiscoveryAnchor $Text ('startParallelPollThread('+$tuple+', uniqueAddresses);') ('startParallelPollThread('+$tuple+', uniqueAddresses, rhFailures);')
}
$Text=Replace-DiscoveryAnchor $Text @'
        return null;
    }

    private boolean pollComputer(ComputerDetails details) throws InterruptedException {
'@ @'
        if(rhFailures!=null){
            com.limelight.rhremote.RhDiscoveryObservation failed=rhFailures.finish();
            if(failed!=null)details.rhDiscoveryObservation=failed;
        }
        return null;
    }

    private boolean pollComputer(ComputerDetails details) throws InterruptedException {
'@
return $Text
