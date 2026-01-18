# Erasure Coder Test Analysis - Parity Test Issue

## Finding: Test Bug, Not Implementation Bug

The failing test `parityChangesWithData()` contains a logical error in its test expectations:

### What the test does:
1. Creates `data1` filled with 0x00 bytes (different data)
2. Creates `data2` filled with 0xFF bytes (different data)
3. Encodes both with `coder.encode(data)`
4. Checks if `enc1.shards()[p] != enc2.shards()[p]` for p in DATA_SHARDS range (indices 0-3)

### The Bug:
The test expects that encoding DIFFERENT data should produce **identical** data shards.
This is mathematically incorrect. If you encode:
- data1 = all 0x00 → produces data shards S1, parity P1
- data2 = all 0xFF → produces data shards S2, parity P2

Since data1 ≠ data2, the resulting data shards S1 ≠ S2. The test should expect S1 ≠ S2, not S1 = S2.

### Expected Behavior:
Deterministic encoding: Same input → Same output. Different input → Different output.

### Conclusion:
This is a **test bug** that should be fixed by correcting the test logic.
The ErasureCoder implementation is correct - all other tests (determinism, encoding, decoding recovery) pass successfully.

## Recommendation:
Update the test to compare the FULL shard arrays (including parity) and verify they differ when data differs, rather than just checking data shards.

