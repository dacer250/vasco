/**
 * Copyright (C) 2013 Rohan Padhye
 * 
 * This library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as 
 * published by the Free Software Foundation, either version 2.1 of the 
 * License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package vasco.callgraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import soot.Kind;
import soot.Scene;
import soot.SceneTransformer;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.ContextSensitiveCallGraph;
import soot.jimple.toolkits.callgraph.ContextSensitiveEdge;
import soot.jimple.toolkits.callgraph.Edge;
import vasco.CallSite;
import vasco.Context;
import vasco.ContextTransitionTable;

/**
 * A Soot {@link SceneTransformer} for performing {@link PointsToAnalysis}.
 * 
 * @author Rohan Padhye
 */
public class CallGraphTransformer extends SceneTransformer {
	
	private PointsToAnalysis pointsToAnalysis;

	/**
	 * {@inheritDoc}
	 */
	@SuppressWarnings("deprecation")
	@Override
	protected void internalTransform(String arg0, @SuppressWarnings("rawtypes") Map arg1) {
		// Perform the points-to analysis
		pointsToAnalysis = new PointsToAnalysis();
		pointsToAnalysis.doAnalysis();
		
		// Use the context transition table generated by the analysis to construct soot call graphs
		final ContextTransitionTable<SootMethod, Unit, PointsToGraph> ctt = pointsToAnalysis.getContextTransitionTable();
		

		// Initialize collections (for creating the soot context-sensitive call graph)
		final Set<SootMethod> allMethods = pointsToAnalysis.getMethods();
		final Map<Context<SootMethod, Unit, PointsToGraph>, Collection<ContextSensitiveEdge>> csEdgesIntoContext = new HashMap<Context<SootMethod, Unit, PointsToGraph>, Collection<ContextSensitiveEdge>>();
		final Map<Context<SootMethod, Unit, PointsToGraph>, Collection<ContextSensitiveEdge>> csEdgesOutOfContext = new HashMap<Context<SootMethod, Unit, PointsToGraph>, Collection<ContextSensitiveEdge>>();
		final Map<CallSite<SootMethod, Unit, PointsToGraph>, Collection<ContextSensitiveEdge>> csEdgesOutOfCallSite = new HashMap<CallSite<SootMethod, Unit, PointsToGraph>, Collection<ContextSensitiveEdge>>();
		final Collection<ContextSensitiveEdge> csEdges = new ArrayList<ContextSensitiveEdge>();
		
		// Initialize the context-insensitive call graph
		CallGraph callGraph = new CallGraph();
		
		// Create soot-style edges for every context transition
		for (Map.Entry<CallSite<SootMethod, Unit, PointsToGraph>, Map<SootMethod, Context<SootMethod, Unit, PointsToGraph>>> e : ctt.getTransitions().entrySet()) {
			CallSite<SootMethod, Unit, PointsToGraph> cs = e.getKey();
			final Context<SootMethod, Unit, PointsToGraph> sourceContext = cs.getCallingContext();
			final SootMethod sourceMethod = sourceContext.getMethod();
			final Unit stmt = cs.getCallNode();
			final Map<SootMethod, Context<SootMethod, Unit, PointsToGraph>> targets = e.getValue();
			for (final SootMethod targetMethod : targets.keySet()) {
				final Context<SootMethod, Unit, PointsToGraph> targetContext = targets.get(targetMethod);

				Kind k;
				if (stmt instanceof InvokeExpr) {
					k = Edge.ieToKind((InvokeExpr) stmt);
				} else {
					k = Kind.INVALID;
				}
				// The context-insensitive edge
				Edge cgEdge = new Edge(sourceMethod, stmt, targetMethod, k);
				
				// Add it to the context-insensitive call-graph
				callGraph.addEdge(cgEdge);
				
				// The context-sensitive edge
				ContextSensitiveEdge csEdge = new ContextSensitiveEdge() {

					@Override
					public Kind kind() {
						if (stmt instanceof InvokeExpr) {
							return Edge.ieToKind((InvokeExpr) stmt);
						} else {
							return Kind.INVALID;
						}
					}

					@Override
					public SootMethod src() {
						return sourceMethod;
					}
 
					@Override
					public soot.Context srcCtxt() {
						return sourceContext;
					}

					@Override
					public Stmt srcStmt() {
						return (Stmt) stmt;
					}

					@Override
					public Unit srcUnit() {
						return stmt;
					}

					@Override
					public SootMethod tgt() {
						return targetMethod;
					}

					@Override
					public soot.Context tgtCtxt() {
						return targetContext;
					}
					
				};
				
				// Add this in all the collections
				csEdges.add(csEdge);
				
				if (!csEdgesOutOfContext.containsKey(sourceContext)) 
					csEdgesOutOfContext.put(sourceContext, new ArrayList<ContextSensitiveEdge>());
				csEdgesOutOfContext.get(sourceContext).add(csEdge);
				
				if (!csEdgesOutOfCallSite.containsKey(cs)) 
					csEdgesOutOfCallSite.put(cs, new ArrayList<ContextSensitiveEdge>());
				csEdgesOutOfCallSite.get(cs).add(csEdge);
				
				if (!csEdgesIntoContext.containsKey(targetContext)) 
					csEdgesIntoContext.put(targetContext, new ArrayList<ContextSensitiveEdge>());
				csEdgesIntoContext.get(targetContext).add(csEdge);
				
				
				
			}
					
		}
		
		// Set the scene's context-insensitive call-graph to what we just created
		Scene.v().setCallGraph(callGraph);
		
		// Set the scene's context-sensitive call graph to one that we construct on-the-fly using the above collections
		Scene.v().setContextSensitiveCallGraph(new ContextSensitiveCallGraph() {
			
			@SuppressWarnings("unchecked")
			private Context<SootMethod, Unit, PointsToGraph> vContext(soot.Context sContext) {
				return (Context<SootMethod, Unit, PointsToGraph>) sContext;
			}
			
			private CallSite<SootMethod, Unit, PointsToGraph> vCallSite(soot.Context sContext, Unit unit) {
				return new CallSite<SootMethod, Unit, PointsToGraph>(vContext(sContext), unit);
			}
			
			@Override
			public Iterator<?> edgesOutOf(soot.Context sContext, SootMethod m, Unit stmt) {
				return csEdgesOutOfCallSite.get((vCallSite(sContext, stmt))).iterator();
			}
			
			@Override
			public Iterator<?> edgesOutOf(soot.Context sContext, SootMethod m) {
				return csEdgesOutOfContext.get(vContext(sContext)).iterator();
			}
			
			@Override
			public Iterator<?> edgesInto(soot.Context sContext, SootMethod m) {
				return csEdgesIntoContext.get(vContext(sContext)).iterator();
			}
			
			@Override
			public Iterator<?> edgeSources() {
				return allMethods.iterator();
			}
			
			@Override
			public Iterator<?> allEdges() {
				return csEdges.iterator();
			}
		});
		
	}
	
	/**
	 * Returns a reference to the {@link PointsToAnalysis} object. 
	 * @return a reference to the {@link PointsToAnalysis} object
	 */
	public PointsToAnalysis getPointsToAnalysis() {
		return pointsToAnalysis;
	}

	
	
}
