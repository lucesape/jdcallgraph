/*
 * MIT License
 * <p>
 * Copyright (c) 2017 David Krebs
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.dkarv.jdcallgraph.callgraph;

import com.dkarv.jdcallgraph.util.options.Target;
import com.dkarv.jdcallgraph.writer.*;
import com.dkarv.jdcallgraph.util.*;
import com.dkarv.jdcallgraph.util.config.Config;
import com.dkarv.jdcallgraph.util.log.Logger;

import java.io.*;
import java.util.*;

public class CallGraph {
  private static final Logger LOG = new Logger(CallGraph.class);
  private static final String FOLDER = "cg/";
  private final long threadId;
  final Stack<StackItem> calls = new Stack<>();
  final Stack<Integer> reads = new Stack<>();

  final List<GraphWriter> writers = new ArrayList<>();

  public CallGraph(long threadId) {
    this.threadId = threadId;
    Target[] targets = Config.getInst().writeTo();
    for (Target target : targets) {
      if (!target.isDataDependency()) {
        // Do not handle the DATA dependence graph
        writers.add(createWriter(target, Config.getInst().multiGraph()));
      }
    }
  }

  static GraphWriter createWriter(Target t, boolean multiGraph) {
    switch (t) {
      case DOT:
        if (multiGraph) {
          return new DotFileWriter();
        } else {
          return new RemoveDuplicatesWriter(new DotFileWriter());
        }
      case MATRIX:
        return new CsvMatrixFileWriter();
      case COVERAGE:
        return new CsvCoverageFileWriter();
      case TRACE:
        return new CsvTraceFileWriter();
      case LINES:
        return new LineNumberFileWriter();
      default:
        throw new IllegalArgumentException("Unknown writeTo: " + t);
    }
  }

  /**
   * Check whether the method is a valid start condition.
   *
   * @param method called method
   * @return identifier if method is a valid start condition, null otherwise
   */
  String checkStartCondition(StackItem method) {
    switch (Config.getInst().groupBy()) {
      case THREAD:
        return FOLDER + String.valueOf(threadId);
      case ENTRY:
        return FOLDER + method.toString();
      default:
        throw new IllegalArgumentException("Unknown groupBy: " + Config.getInst().groupBy());
    }
  }

  public void called(StackItem method) throws IOException {
    reads.push(0);
    if (calls.isEmpty()) {
      // First node
      String identifier = checkStartCondition(method);
      if (identifier != null) {
        calls.push(method);
        for (GraphWriter w : writers) {
          w.start(identifier);
          w.node(method);
        }
      } else {
        LOG.info("Skip first node {} because start condition not fulfilled", method);
      }
    } else {
      // There already is at least one node, so this is an edge
      for (GraphWriter w : writers) {
        w.edge(calls.peek(), method);
      }
      calls.push(method);
    }
  }

  public void returned(StackItem method) throws IOException {
    //if (!reads.isEmpty()) {
    //  int r = reads.pop();
    //  if (r != 0) {
    //    LOG.info("{} did reads: {}", method, r);
    //  }
    //}
    Stack<StackItem> trace = new Stack<>();
    int removed = 0;
    boolean found = false;
    while (!calls.isEmpty() && !found) {
      removed++;
      StackItem topItem = calls.pop();
      trace.push(topItem);
      if (topItem.equals(method)) {
        found = true;
      }
    }
    if (removed != 1) {
      LOG.warn("Error when method {} returned:", method);
      LOG.warn("Removed {} entries. Stack trace {}", removed, trace);
    }
    if (!found) {
      LOG.warn("Couldn't find the returned method call on stack");
    }
    if (calls.isEmpty()) {
      for (GraphWriter w : writers) {
        w.end();
      }
    }
  }

  public void dataEdge(StackItem from, StackItem to) throws IOException {
    if (!reads.isEmpty()) {
      reads.push(reads.pop() + 1);
    }
    if (calls.isEmpty()) {
      LOG.info("Ignore dd egde {} -> {}", from, to);
    } else {
      for (GraphWriter w : writers) {
        if (w instanceof DotFileWriter || w instanceof RemoveDuplicatesWriter) {
          w.edge(from, to, "[style=dotted]");
        }
      }
    }
  }

  public void finish() throws IOException {
    if (!calls.isEmpty()) {
      LOG.error("Shutdown but call graph not empty: {}", calls);
      for (GraphWriter w : writers) {
        w.end();
      }
    }

    for (GraphWriter w : writers) {
      w.close();
    }
  }
}
