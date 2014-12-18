HeapFragger
===========

HeapFragger: A heap fragmentation inducer, meant to induce compaction of
the heap on a regular basis using a limited (and settable) amount of
CPU and memory resources.

The purpose of HeapFragger is [among other things] to aid application
testers in inducing inevitable-but-rare garbage collection events,
such that they would occur on a regular and more frequent and reliable
basis. Doing so allows the characterization of system behavior, such
as response time envelope, within practical test cycle times.

HeapFragger works on the simple basis of repeatedly generating large sets
of objects of a given size, pruning each set down to a much smaller
remaining live set after it has been promoted, and increasing the object
size between passes such that is becomes unlikely to fit in the areas freed
up by objects released in a previous pass without some amount of compaction.
HeapFragger ages object sets before pruning them down in order to bypass
potential artificial early compaction by young generation collectors.

By the time enough passes are done such that aggregate space allocated
by the passes roughly matches the heap size (although a much smaller
percentage is actually alive), some level of compaction likely or
inevitable.

HeapFragger's resource consumption is completely tunable, it will throttle
itself to a tunable rate of allocation, and limit it's heap footprint
to configurable level. When run with default settings, HeapFragger will
occupy 10% of total heap space, and allocate objects at a rate
of 50MB/sec.

Altering the allocation rate and the peakMBPerIncrement parameter
(a larger peakMBPerIncrement will require fewer churning passes in each
compaction-inducing iteration), will change the frequency with which
compactions occur.The main (common) settable items are:

- allocMBsPerSec [-a, default: 50]: Allocation rate - controls the CPU %
HeapFragger occupies, and affects compaction event freq.

- heapBudgetAsFraction [-f, default: 0.1] or
heapBudgetInMB [-b, default: derived from heapBudgetAsFraction] can
be used to controls the peak amount of heap space that the HeapFragger
will use for it's temporary churning storage needs. While the default
setting is to use ~10% of the detected heap size, higher heap budgets
can be used to allow HeapFragger to fragment the heap more quickly.

- heapMBtoSitOn [-s, default: 0]: Useful for experimenting with the
effects of varying heap occupancies on compaction times. Causes
HeapFragger to pre-allocate an additional static live set of the given
size.

- pauseThresholdMs [-t, default: 350]: For convenience, HeapFragger includes
a simple pause detector that will report on detected pauses to stderr. The
pauseThresholdMs parameter controls the threshold below which detected
pauses will not be reported.

HeapFragger will typically be added to existing Java applications as a
javaagent. E.g. a typical command line will be:

java ... -javaagent:HeapFragger.jar="-a 100" MyApp myAppArgs

The HeapFragger jar also includes a convenient Idle class that can be
used for demo purposes. E.g. the following command line will demonstrate
periodic promotion-failure related pauses with the HotSpot CMS collector,
with each resulting Full GC having to deal with at least 512MB of live
matter in the heap:

java -Xmx2g -Xmx2g -XX:+UseConcMarkSweepGC -XX:+PrintGCApplicationStoppedTime
  -XX:+PrintGCDetails -Xloggc:gc.log -javaagent:HeapFragger.jar="-a 400 -s 512"
    org.HeapFragger.Idle -t 1000000000
