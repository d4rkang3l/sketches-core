package com.yahoo.sketches.sampling;

import static com.yahoo.sketches.sampling.PreambleUtil.FAMILY_BYTE;
import static com.yahoo.sketches.sampling.PreambleUtil.PREAMBLE_LONGS_BYTE;
import static com.yahoo.sketches.sampling.PreambleUtil.SER_VER_BYTE;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import org.testng.annotations.Test;

import com.yahoo.memory.Memory;
import com.yahoo.memory.NativeMemory;
import com.yahoo.sketches.ArrayOfLongsSerDe;
import com.yahoo.sketches.ArrayOfStringsSerDe;
import com.yahoo.sketches.Family;
import com.yahoo.sketches.SketchesArgumentException;
import com.yahoo.sketches.hll.Preamble;

public class VarOptItemsSketchTest {
  public static final double EPS = 1e-10;

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkInvalidK() {
    VarOptItemsSketch.<Integer>getInstance(0);
    fail();
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkBadSerVer() {
    final VarOptItemsSketch<Long> sketch = getUnweightedLongsVIS(16, 16);
    final byte[] bytes = sketch.toByteArray(new ArrayOfLongsSerDe());
    final Memory mem = new NativeMemory(bytes);

    mem.putByte(SER_VER_BYTE, (byte) 0); // corrupt the serialization version

    VarOptItemsSketch.getInstance(mem, new ArrayOfLongsSerDe());
    fail();
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkBadFamily() {
    final VarOptItemsSketch<Long> sketch = getUnweightedLongsVIS(32, 16);
    final byte[] bytes = sketch.toByteArray(new ArrayOfLongsSerDe());
    final Memory mem = new NativeMemory(bytes);

    mem.putByte(FAMILY_BYTE, (byte) 0); // corrupt the family ID

    VarOptItemsSketch.getInstance(mem, new ArrayOfLongsSerDe());
    fail();
  }

  @Test
  public void checkBadPreLongs() {
    final VarOptItemsSketch<Long> sketch = getUnweightedLongsVIS(32, 33);
    final byte[] bytes = sketch.toByteArray(new ArrayOfLongsSerDe());
    final Memory mem = new NativeMemory(bytes);

    mem.putByte(PREAMBLE_LONGS_BYTE, (byte) 0); // corrupt the preLongs count to 0
    try {
      VarOptItemsSketch.getInstance(mem, new ArrayOfLongsSerDe());
      fail();
    } catch (final SketchesArgumentException e) {
      // expected
    }

    // corrupt the preLongs count to be too large
    mem.putByte(PREAMBLE_LONGS_BYTE, (byte) (Family.VAROPT.getMaxPreLongs() + 1));
    try {
      VarOptItemsSketch.getInstance(mem, new ArrayOfLongsSerDe());
      fail();
    } catch (final SketchesArgumentException e) {
      // expected
    }
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkBadMemory() {
    byte[] bytes = new byte[4];
    Memory mem = new NativeMemory(bytes);

    try {
      PreambleUtil.getAndCheckPreLongs(mem);
      fail();
    } catch (final SketchesArgumentException e) {
      // expected
    }

    bytes = new byte[8];
    bytes[0] = 2; // only 1 preLong worth of data in bytearray
    mem = new NativeMemory(bytes);
    PreambleUtil.getAndCheckPreLongs(mem);
  }

  @Test
  public void checkMalformedPreamble() {
    final int k = 50;
    final VarOptItemsSketch<Long> sketch = getUnweightedLongsVIS(k, k);

    final byte[] sketchBytes = sketch.toByteArray(new ArrayOfLongsSerDe());
    final Memory srcMem = new NativeMemory(sketchBytes);

    final byte[] copyBytes = new byte[sketchBytes.length];
    final Memory mem = new NativeMemory(copyBytes);
    NativeMemory.copy(srcMem, 0, mem, 0, sketchBytes.length);

    final Object memObj = mem.array(); // may be null
    final long memAddr = mem.getCumulativeOffset(0L);
    assertEquals(PreambleUtil.extractPreLongs(memObj, memAddr), 2);
    PreambleUtil.insertPreLongs(memObj, memAddr, 3); //

    try {
      VarOptItemsSketch.getInstance(mem, new ArrayOfLongsSerDe());
      fail();
    } catch (final SketchesArgumentException e) {
      assertTrue(e.getMessage().startsWith("Possible Corruption: 3 preLongs but"));
    }
  }

  @Test
  public void checkEmptySketch() {
    final VarOptItemsSketch<String> vis = VarOptItemsSketch.getInstance(5);
    assertNull(vis.getSamples());
    assertNull(vis.getSamples(Long.class));

    final byte[] sketchBytes = vis.toByteArray(new ArrayOfStringsSerDe());
    final Memory mem = new NativeMemory(sketchBytes);

    // only minPreLongs bytes and should deserialize to empty
    assertEquals(sketchBytes.length, Family.VAROPT.getMinPreLongs() << 3);
    final ArrayOfStringsSerDe serDe = new ArrayOfStringsSerDe();
    final VarOptItemsSketch<String> loadedVis = VarOptItemsSketch.getInstance(mem, serDe);
    assertEquals(loadedVis.getNumSamples(), 0);

    println("Empty sketch:");
    println("  Preamble:");
    println(PreambleUtil.preambleToString(mem));
    println("  Sketch:");
    println(vis.toString());
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkInvalidWeight() {
    final VarOptItemsSketch<String> vis = VarOptItemsSketch.getInstance(5);
    try {
      vis.update(null, 1.0); // should work fine
    } catch (final SketchesArgumentException e) {
      fail();
    }

    vis.update("invalidWeight", -1.0); // should fail
  }

  @Test
  public void checkCumulativeWeight() {
    final int k = 256;
    final VarOptItemsSketch<Long> sketch = VarOptItemsSketch.getInstance(k);

    double inputSum = 0.0;
    for (long i = 0; i < 10 * k; ++i) {
      // generate weights above and below 1.0 using w ~ exp(5*N(0,1)) which covers about
      // 10 orders of magnitude
      final double w = Math.exp(5 * SamplingUtil.rand.nextGaussian());
      inputSum += w;
      sketch.update(i, w);
    }

    final VarOptItemsSketch.Result samples = sketch.getSamples();
    final double[] weights = samples.weights;
    double outputSum = 0;
    for (double w : weights) {
      outputSum += w;
    }

    final double wtRatio = outputSum / inputSum;
    assertTrue(Math.abs(wtRatio - 1.0) < EPS);
  }

  @Test
  public void checkUnderfullSketchSerialization() {
    final VarOptItemsSketch<Long> sketch = VarOptItemsSketch.getInstance(2048);
    for (long i = 0; i < 10; ++i) {
      sketch.update(i, 1.0);
    }
    assertEquals(sketch.getNumSamples(), 10);

    final byte[] bytes = sketch.toByteArray(new ArrayOfLongsSerDe());
    final Memory mem = new NativeMemory(bytes);

    // ensure 2 preLongs
    final Object memObj = mem.array(); // may be null
    final long memAddr = mem.getCumulativeOffset(0L);
    assertEquals(PreambleUtil.extractPreLongs(memObj, memAddr), 2);

    final VarOptItemsSketch<Long> rebuilt
            = VarOptItemsSketch.getInstance(mem, new ArrayOfLongsSerDe());
    checkIfEqual(rebuilt, sketch);
  }

  @Test
  public void checkFullSketchSerialization() {
    final VarOptItemsSketch<Long> sketch = VarOptItemsSketch.getInstance(32);
    for (long i = 0; i < 32; ++i) {
      sketch.update(i, 1.0);
    }
    sketch.update(100L, 100.0);
    sketch.update(101L, 101.0);
    assertEquals(sketch.getNumSamples(), 32);

    // first 2 entries should be heavy and in heap order (smallest at root)
    final VarOptItemsSketch.Result result = sketch.getSamples();
    assertTrue(result.weights[0] == 100.0);
    assertTrue(result.weights[1] == 101.0);

    final byte[] bytes = sketch.toByteArray(new ArrayOfLongsSerDe());
    final Memory mem = new NativeMemory(bytes);

    // ensure 3 preLongs
    final Object memObj = mem.array(); // may be null
    final long memAddr = mem.getCumulativeOffset(0L);
    assertEquals(PreambleUtil.extractPreLongs(memObj, memAddr), 3);

    final VarOptItemsSketch<Long> rebuilt
            = VarOptItemsSketch.getInstance(mem, new ArrayOfLongsSerDe());
    checkIfEqual(rebuilt, sketch);
  }

  @Test
  public void checkPseudoLightUpdate() {
    final int k = 1024;
    final VarOptItemsSketch<Long> sketch = getUnweightedLongsVIS(k, k + 1);
    sketch.update(0L, 1.0); // k+2-nd update

    // checking weight[0], assuming all k items are unweighted (and consequently in R)
    // Expected: (k + 2) / |R| = (k+2) / k
    final VarOptItemsSketch.Result result = sketch.getSamples();
    final double wtDiff = result.weights[0] - 1.0 * (k + 2) / k;
    assertTrue(Math.abs(wtDiff) < EPS);
  }

  @Test
  public void checkPseudoHeavyUpdates() {
    final int k = 1024;
    final double wtScale = 10.0 * k;
    final VarOptItemsSketch<Long> sketch = VarOptItemsSketch.getInstance(k);
    for (long i = 0; i <= k; ++i) {
      sketch.update(i, 1.0);
    }

    // Next k-1 updates should be updatePseudoHeavyGeneral()
    // Last one should cat updatePseudoHeavyREq1(),since we'll have added k-1 heavy
    // items, leaving only 1 item left in R
    for (long i = 1; i <= k; ++i) {
      sketch.update(-i, k + (i * wtScale));
    }

    final VarOptItemsSketch.Result result = sketch.getSamples();

    // Don't know which R item is left, but should be only one at the end of the array
    // Expected: k+1 + (min "heavy" item) / |R| = ((k+1) + (k+wtScale)) / 1 = wtScale + 2k + 1
    double wtDiff = result.weights[k - 1] - 1.0 * (wtScale + 2 * k + 1);
    assertTrue(Math.abs(wtDiff) < EPS);

    // Expected: 2nd lightest "heavy" item: k + 2*wtScale
    wtDiff = result.weights[0] - 1.0 * (k + 2 * wtScale);
    assertTrue(Math.abs(wtDiff) < EPS);
  }


  /* Returns a sketch of size k that has been presented with n items. Use n = k+1 to obtain a
     sketch that has just reached the sampling phase, so that the next update() is handled by
     one of hte non-warmup routes.
   */
  private VarOptItemsSketch<Long> getUnweightedLongsVIS(final int k, final int n) {
    final VarOptItemsSketch<Long> sketch = VarOptItemsSketch.getInstance(k);
    for (long i = 0; i < n; ++i) {
      sketch.update(i, 1.0);
    }

    return sketch;
  }

  private <T> void checkIfEqual(VarOptItemsSketch<T> s1, VarOptItemsSketch<T> s2) {
    assertEquals(s1.getK(), s2.getK(), "Sketches have different values of k");
    assertEquals(s1.getNumSamples(), s2.getNumSamples(), "Sketches have different sample counts");

    final int len = s1.getNumSamples();
    final VarOptItemsSketch.Result r1 = s1.getSamples();
    final VarOptItemsSketch.Result r2 = s2.getSamples();

    for (int i = 0; i < len; ++i) {
      assertEquals(r1.data[i], r2.data[i], "Data values differ at sample " + i);
      assertEquals(r1.weights[i], r2.weights[i], "Weights differ at sample " + i);
    }
  }

  /**
   * Wrapper around System.out.println() allowing a simple way to disable logging in tests
   * @param msg The message to print
   */
  private static void println(String msg) {
    //System.out.println(msg);
  }
}