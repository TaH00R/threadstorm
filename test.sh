#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
[ -d out ] || ./build.sh
for test in \
  tests.PrioritySchedulingTest \
  tests.FaultToleranceTest \
  tests.ObservabilityTest \
  ThreadStormStressTest \
  tests.BenchmarkSmokeTest \
  tests.TaskCoordinationTest \
  tests.LifecycleHardeningTest \
  tests.CancellationHardeningTest \
  tests.TuiEngineIntegrationTest \
  cli.TerminalInputSmokeTest
do
  echo "=== $test ==="
  java -cp out "$test"
done
echo 'All 10 test programs passed.'
