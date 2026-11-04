package tests;

import benchmarks.BenchmarkRunner;

/** Manual smoke-test instructions are provided in README; benchmark runner has no test-only API. */
public final class BenchmarkSmokeTest {
    private BenchmarkSmokeTest() { }
    public static void main(String[] args) throws Exception {
        BenchmarkRunner.main(new String[] {"--tasks", "40", "--work", "100", "--workers", "2", "--warmups", "0", "--runs", "1", "--csv", "benchmark-smoke.csv"});
        System.out.println("Benchmark smoke test completed.");
    }
}
