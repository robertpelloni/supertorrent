package io.supernode.storage.erasure;

import java.util.Arrays;

public class ErasureCoder {
    private static final int GF_SIZE = 256;
    private static final int PRIMITIVE_POLY = 0x11d;

    private final int dataShards;
    private final int parityShards;
    private final int totalShards;

    private final int[] expTable = new int[GF_SIZE * 2];
    private final int[] logTable = new int[GF_SIZE];
    private final int[][] encodeMatrix;

    public ErasureCoder() {
        this(4, 2);
    }

    public ErasureCoder(int dataShards, int parityShards) {
        if (dataShards < 1 || parityShards < 1) {
            throw new IllegalArgumentException("Must have at least 1 data and 1 parity shard");
        }
        if (dataShards + parityShards > 255) {
            throw new IllegalArgumentException("Total shards cannot exceed 255");
        }

        this.dataShards = dataShards;
        this.parityShards = parityShards;
        this.totalShards = dataShards + parityShards;

        initGaloisTables();
        this.encodeMatrix = buildVandermondeMatrix();
    }

    public EncodeResult encode(byte[] data) {
        int shardSize = (data.length + dataShards - 1) / dataShards;
        byte[] paddedData = Arrays.copyOf(data, shardSize * dataShards);

        byte[][] shards = new byte[totalShards][shardSize];

        for (int i = 0; i < dataShards; i++) {
            System.arraycopy(paddedData, i * shardSize, shards[i], 0, shardSize);
        }

        for (int i = dataShards; i < totalShards; i++) {
            for (int j = 0; j < shardSize; j++) {
                int value = 0;
                for (int k = 0; k < dataShards; k++) {
                    value ^= gfMul(encodeMatrix[i][k], shards[k][j] & 0xFF);
                }
                shards[i][j] = (byte) value;
            }
        }

        return new EncodeResult(shards, shardSize, data.length);
    }

    public byte[] decode(byte[][] shards, int[] presentIndices, int originalSize, int shardSize) {
        if (presentIndices.length < dataShards) {
            throw new IllegalArgumentException(
                "Need at least " + dataShards + " shards, got " + presentIndices.length);
        }

        int[] selectedIndices = Arrays.copyOf(presentIndices, dataShards);
        byte[][] selectedShards = new byte[dataShards][];
        for (int i = 0; i < dataShards; i++) {
            selectedShards[i] = shards[selectedIndices[i]];
        }

        int[][] subMatrix = new int[dataShards][dataShards];
        for (int i = 0; i < dataShards; i++) {
            System.arraycopy(encodeMatrix[selectedIndices[i]], 0, subMatrix[i], 0, dataShards);
        }

        int[][] invMatrix = invertMatrix(subMatrix);

        byte[][] decoded = new byte[dataShards][shardSize];
        for (int i = 0; i < dataShards; i++) {
            for (int j = 0; j < shardSize; j++) {
                int value = 0;
                for (int k = 0; k < dataShards; k++) {
                    value ^= gfMul(invMatrix[i][k], selectedShards[k][j] & 0xFF);
                }
                decoded[i][j] = (byte) value;
            }
        }

        byte[] result = new byte[originalSize];
        int offset = 0;
        for (int i = 0; i < dataShards && offset < originalSize; i++) {
            int toCopy = Math.min(shardSize, originalSize - offset);
            System.arraycopy(decoded[i], 0, result, offset, toCopy);
            offset += toCopy;
        }

        return result;
    }

    private void initGaloisTables() {
        int x = 1;
        for (int i = 0; i < GF_SIZE - 1; i++) {
            expTable[i] = x;
            expTable[i + GF_SIZE - 1] = x;
            logTable[x] = i;
            x <<= 1;
            if (x >= GF_SIZE) {
                x ^= PRIMITIVE_POLY;
            }
        }
        logTable[0] = 0;
    }

    private int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return expTable[logTable[a] + logTable[b]];
    }

    private int gfDiv(int a, int b) {
        if (b == 0) throw new ArithmeticException("Division by zero in GF");
        if (a == 0) return 0;
        return expTable[(logTable[a] - logTable[b] + (GF_SIZE - 1)) % (GF_SIZE - 1)];
    }

    private int gfInv(int a) {
        if (a == 0) throw new ArithmeticException("Cannot invert zero in GF");
        return expTable[(GF_SIZE - 1) - logTable[a]];
    }

    private int[][] buildVandermondeMatrix() {
        int[][] matrix = new int[totalShards][dataShards];

        for (int i = 0; i < dataShards; i++) {
            matrix[i][i] = 1;
        }

        for (int i = dataShards; i < totalShards; i++) {
            for (int j = 0; j < dataShards; j++) {
                int base = i - dataShards + 1;
                matrix[i][j] = gfPow(base, j);
            }
        }

        return matrix;
    }

    private int gfPow(int base, int exp) {
        if (exp == 0) return 1;
        int result = 1;
        for (int i = 0; i < exp; i++) {
            result = gfMul(result, base);
        }
        return result;
    }

    private int[][] invertMatrix(int[][] matrix) {
        int n = matrix.length;
        int[][] work = new int[n][n * 2];

        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, work[i], 0, n);
            work[i][n + i] = 1;
        }

        for (int i = 0; i < n; i++) {
            if (work[i][i] == 0) {
                int swapRow = -1;
                for (int j = i + 1; j < n; j++) {
                    if (work[j][i] != 0) {
                        swapRow = j;
                        break;
                    }
                }
                if (swapRow == -1) {
                    throw new IllegalArgumentException("Matrix is not invertible");
                }
                int[] temp = work[i];
                work[i] = work[swapRow];
                work[swapRow] = temp;
            }

            int inv = gfInv(work[i][i]);
            for (int j = 0; j < 2 * n; j++) {
                work[i][j] = gfMul(work[i][j], inv);
            }

            for (int j = 0; j < n; j++) {
                if (j != i && work[j][i] != 0) {
                    int factor = work[j][i];
                    for (int k = 0; k < 2 * n; k++) {
                        work[j][k] ^= gfMul(factor, work[i][k]);
                    }
                }
            }
        }

        int[][] result = new int[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(work[i], n, result[i], 0, n);
        }

        return result;
    }

    public int getDataShards() { return dataShards; }
    public int getParityShards() { return parityShards; }
    public int getTotalShards() { return totalShards; }

    public record EncodeResult(byte[][] shards, int shardSize, int originalSize) {}
}
