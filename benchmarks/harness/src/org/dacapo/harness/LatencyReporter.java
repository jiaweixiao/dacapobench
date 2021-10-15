/*
 * Copyright (c) 2006, 2009, 2020 The Australian National University.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License v2.0.
 * You may obtain the license at
 * 
 *    http://www.opensource.org/licenses/apache2.0.php
 */
package org.dacapo.harness;

import java.util.Arrays;
import java.lang.reflect.Method;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.NoSuchMethodException;
/**
 * Thread-local latency reporter used to generate tail-latency stats in
 * a resource-sensitive, contention-free, statistically-robust way.
 */
public class LatencyReporter {
  static final int TAIL_PRECISION = 10000;  // report tail to 1/TAIL_PRECISION
  static final int LATENCY_BUFFER_SIZE = 1000 * TAIL_PRECISION;
  static final int NS_COARSENING = 1;   // measure at this precision
  static final int US_DIVISOR = 1000/NS_COARSENING;

  private int idxOffset;
  private int idx;
  private long start;
  private double max;

  static float[] txbegin;
  static float[] txend;
  static double fileMax;
  static LatencyReporter[] reporters;
  private static long timerBase = 0;  // used to improve precision (when using floats)

  public LatencyReporter(int threadID, int threads, int transactions) {
    this(threadID, threads, transactions, 1);
  }
  public LatencyReporter(int threadID, int threads, int transactions, int batchSize) {
    idx = idxOffset = getBaseIdx(threadID, threads, transactions, batchSize);
    max = 0;
    reporters[threadID] = this;
  }

  public static void initialize(int threads) {
    timerBase = System.nanoTime();
  }
  
  public static void initialize(int transactions, int threads) {
    initialize(transactions, threads, 1);
  }

  public static void initialize(int transactions, int threads, int batchSize) {
    timerBase = System.nanoTime();
    if (transactions > LATENCY_BUFFER_SIZE) {
      System.err.println("Too many transactions. "+transactions+" > LATENCY_BUFFER_SIZE ("+LATENCY_BUFFER_SIZE+")");
      java.lang.Thread.dumpStack();
      System.exit(-1);
    } else {
      txbegin = new float[transactions];
      txend = new float[transactions];
      reporters = new LatencyReporter[threads];
      for (int i = 0; i < threads; i++) {
        reporters[i] = new LatencyReporter(i, threads, transactions, batchSize);
      }
    }
  }

  public static LatencyReporter[] getLatencyReporters() {
    return reporters;
  }

  private static int getBaseIdx(int threadID, int threads, int transactions, int batchSize) {
    int batches = transactions / batchSize;
    if (transactions % batchSize != 0) {
      System.out.println("Number of transactions is not multiple of batch size");
      System.exit(-1);
    }
    int batchesPerThread = batches / threads;
    int extra = batches % threads;
    if (threadID < extra) {
        return batchSize * threadID * (batchesPerThread + 1);
    } else {
        return batchSize * (extra + (threadID * batchesPerThread));
    }
  }


  /*
   * We explicitly track the max only because it is necessary to do so in cases
   * where we need to sample (otherwise we can trivially find the max as the
   * highest value in our array of latencies).
   */
  private static double getMax() {
    if (System.getProperty("dacapo.latency.file") != null)
      return fileMax/US_DIVISOR;
    
    double max = 0;
    for (LatencyReporter r : reporters)
      if (r.max > max) max = r.max;
    return max/US_DIVISOR;
  }

  public static void reportLatency() {
    if (timerBase != 0) {
      int events = 0;

      if (System.getProperty("dacapo.latency.file") != null) {
        events = readLatencyFile();
      } else {
        for (int i = 0; i < reporters.length; i++)
          events += (reporters[i].idx-reporters[i].idxOffset);

        // check values were correctly added to txbegin and txend arrays
        for (int i = 0; i < reporters.length; i++) {
          int tgt = (i == reporters.length - 1) ? txbegin.length : reporters[i+1].idxOffset;
          if (reporters[i].idx != tgt) {
            System.err.println("Warning: latency report disagreement for thread "+i+".  Expected to fill to offset "+tgt+" but filled to "+reporters[i].idx+" ... "+(reporters[i].idx-tgt)+" "+(txbegin.length / reporters.length));
          }
        }
      }

      // raw latency numbers
      int[] latency = new int[txbegin.length];
      for(int i = 0; i < txbegin.length; i++) {
        latency[i] = (int) ((txend[i] - txbegin[i])/1000);
      }
      printLatency(latency, events, "simple ");

      // synthetically metered --- each query start is evenly spaced, so delays will compound
      float[] sorted = Arrays.copyOf(txbegin, txbegin.length);
      Arrays.sort(sorted);
      double len = sorted[sorted.length-1]-sorted[0];
      double synthstart = 0;
      for(int i = 0; i < txbegin.length; i++) {
        int pos = Arrays.binarySearch(sorted, txbegin[i]);
        synthstart = sorted[0] + (len*(double) pos / (double) txbegin.length);
        int actual = (int) ((txend[i] - txbegin[i])/1000);
        int synth = (int) ((txend[i] - synthstart)/1000);
        latency[i] = (synth > actual) ? synth : actual;
      }
      printLatency(latency, events, "metered ");
    }
  }

  private static String latency(int[] latency, int numerator, int denominator) {
    int usecs = (latency[latency.length - 1 - (latency.length * numerator) / denominator]);
    return ""+usecs+" usec";
  }


  public static void printLatency(int[] latency, int events, String kind) {
    Arrays.sort(latency);
    String report = "===== DaCapo "+kind+"tail latency: ";
    report += "50% " + latency(latency, 50, 100);
    int precision = 10;
    String precstr = "90";
    while (precision <= TAIL_PRECISION) {
      report += ", " + precstr + "% " + latency(latency, 1, precision);
      precision *= 10;
      if (precstr.equals("90"))
        precstr = "99";
      else
        precstr += precstr.equals("99") ? ".9" : "9";
    }
    report += ", max "+((int) latency[latency.length-1])+" usec";
    report += ", measured over "+events+" events =====";
    System.out.println(report);
  }

  public int start() {
    int index = idx++;
    _start(index);
    return index;
  }

  private void _start(int index) {
      start = (System.nanoTime() - timerBase)/NS_COARSENING;
      txbegin[index] = (float) start;
      txend[index] = -1;
      // long start_cast = Double.valueOf(txbegin[index]).longValue();
      // if (start_cast != start) {
      //   System.err.println("WARNING: Timing precision error: "+start+" != "+start_cast);
      // }
  }

  public static int start(int threadID) {
    return reporters[threadID].start();
  }
  
  public static void end(int threadID) {
    reporters[threadID].end();
  }
  
  public static void end(int threadID, int index) {
    reporters[threadID].endI(index);
  }

  public void end() {
    endI(idx-1);
  }

  public void endI(int index) {
    long end = (System.nanoTime() - timerBase)/NS_COARSENING;
    txend[index] = (float) end;
    if (txend[index] > max) max = txend[index];
  }

  private static int readLatencyFile() {
    int idx = 0;
    try {
      File file = new File(System.getProperty("dacapo.latency.file"));
      BufferedReader latencyFile = new BufferedReader(new FileReader(file));

      String line = latencyFile.readLine();
      int entries = Integer.valueOf(line.trim());
      txbegin = new float[entries];
      txend = new float[entries];
      fileMax = 0;

      line = latencyFile.readLine();
      while(line != null) {
        String[] v = line.split(", ");
        txbegin[idx] = Float.valueOf(v[0]);
        txend[idx] = Float.valueOf(v[1]);
        float l = txend[idx]-txbegin[idx];
        if (l > fileMax) fileMax = l;
        line = latencyFile.readLine();
        idx++;
      }
      latencyFile.close();

    } catch(IOException ioe) {
      ioe.printStackTrace();
    }
    return idx;
  }
}