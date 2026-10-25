# A successful client window must be covered by a still-running movie source.
# No process, display, service, filesystem or network changes occur here.
function Test-RHRemoteSourceWindow {
 param([Parameter(Mandatory)]$Before,[Parameter(Mandatory)]$After,[Parameter(Mandatory)]$MeasurementSeconds)
 $number={param($Value,$Name)
  if($Value-isnot[ValueType]-or$Value-is[bool]){throw ('Unknown/non-numeric source window value: '+$Name)}
  $n=[double]$Value
  if([double]::IsNaN($n)-or[double]::IsInfinity($n)){throw ('Non-finite source window value: '+$Name)}
  $n
 }
 $measured=& $number $MeasurementSeconds 'measurementSeconds'
 if($measured-le0){throw 'Client measurement duration must be positive'}
 foreach($endpoint in @($Before,$After)){
  if($endpoint.phase-ne'playing'-or$endpoint.ended-isnot[bool]-or$endpoint.ended-or$endpoint.error){throw ('Source did not remain playing: '+$endpoint.error)}
 }
 $begin=& $number $Before.elapsed_s 'begin source clock'
 $end=& $number $After.elapsed_s 'end source clock'
 $span=$end-$begin
 if($span-lt$measured){throw 'Actual source counter scope does not cover the client measurement'}
 [pscustomobject]@{sourceSeconds=$span;measurementSeconds=$measured;controllerHealthy=$true;measurementCovered=$true;physicalPaintMeasured=$false}
}
