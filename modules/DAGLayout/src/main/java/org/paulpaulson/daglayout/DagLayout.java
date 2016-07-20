/*
 Copyright 2008-2011 Gephi
 Authors : Maik Niggemann <m.ngm@gmx.de>
 Website : http://www.gephi.org

 This file is part of Gephi.

 DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.

 Copyright 2011 Gephi Consortium. All rights reserved.

 The contents of this file are subject to the terms of either the GNU
 General Public License Version 3 only ("GPL") or the Common
 Development and Distribution License("CDDL") (collectively, the
 "License"). You may not use this file except in compliance with the
 License. You can obtain a copy of the License at
 http://gephi.org/about/legal/license-notice/
 or /cddl-1.0.txt and /gpl-3.0.txt. See the License for the
 specific language governing permissions and limitations under the
 License.  When distributing the software, include this License Header
 Notice in each file and include the License files at
 /cddl-1.0.txt and /gpl-3.0.txt. If applicable, add the following below the
 License Header, with the fields enclosed by brackets [] replaced by
 your own identifying information:
 "Portions Copyrighted [year] [name of copyright owner]"

 If you wish your version of this file to be governed by only the CDDL
 or only the GPL Version 3, indicate your decision by adding
 "[Contributor] elects to include this software in this distribution
 under the [CDDL or GPL Version 3] license." If you do not indicate a
 single choice of license, a recipient has the option to distribute
 your version of this file under either the CDDL, the GPL Version 3 or
 to extend the choice of license to its licensees as provided above.
 However, if you add GPL Version 3 code and therefore, elected the GPL
 Version 3 license, then the option applies only if the new code is
 made subject to such option by the copyright holder.

 Contributor(s):

 Portions Copyrighted 2011 Gephi Consortium.
 */
package org.paulpaulson.daglayout;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import org.gephi.graph.api.DirectedGraph;
import org.gephi.graph.api.Edge;
import org.gephi.graph.api.EdgeIterable;
import org.gephi.graph.api.GraphModel;
import org.gephi.graph.api.GraphView;
import org.gephi.graph.api.Node;
import org.gephi.graph.api.NodeIterable;
import org.gephi.layout.spi.Layout;
import org.gephi.layout.spi.LayoutBuilder;
import org.gephi.layout.spi.LayoutProperty;

/**
 * Simple Layout for directed acyclic graphs (DAGs). The nodes are arranged in
 * discrete layers so that the edges will always point downwards (if no loop
 * exists). The nodes are arranged as far to the top as possible. The horizontal
 * layout is done by assigning the nodes to discrete slots in each layer. While
 * running, slots are chosen randomly and swapped if this would make the edges
 * shorter.
 *
 * @author Maik Niggemann
 */
public class DagLayout implements Layout {

    //Architecture
    private final LayoutBuilder builder;
    private GraphModel graphModel;
    //Flags
    private boolean executing = false;
    //Properties
    private int xDistance;
    private int yDistance;
    private float speed;
    private int maxSlot;
    private Node grid[][];
    private int randomizationsPerPass;

    public DagLayout(DagLayoutBuilder builder) {
        this.builder = builder;
    }

    @Override
    public void resetPropertiesValues() {
        xDistance = 100;
        yDistance = 100;
        speed = 0.00004f;
        randomizationsPerPass = 20;
    }

    @Override
    public void initAlgo() {
        executing = true;
        maxSlot = 0;
        grid = null;
        DirectedGraph diGraph = graphModel.getDirectedGraph();

        diGraph.readLock();

        initLayoutData(diGraph);

        // *Handle loops*
        //Nodes will be assigned to layers after their predecessores have been 
        //assigned. This will never happen for loops. Altough this layout is 
        //designed for DAG which are acyclic, this should be handled to get at 
        //least a result that might still be useful in some way.
        //For each loop, an edge has to be ignored to pretend that the graph is
        //a DAG
        fixLoops(diGraph);


        //find root ndes without predecessors. They will be assigned to the 
        //layer 0
        List<Node> rootNodes = findRoots(diGraph);

        //init datastructure which will hold the nodes which will be assigned to 
        // the current layer
        List<Node> currentLayerNodes = rootNodes;


        //find the layer for each node. each node will be in a lower layer than 
        //each of its predecessors. each node will be as far up as possible.
        int currentDepth = 0;
        maxSlot = 0;
        while (!currentLayerNodes.isEmpty()) {
            List<Node> nextLayerNodes = new LinkedList<Node>();
            int slot = 0;
            for (Node currentNode : currentLayerNodes) {
                DagLayoutData layoutData = getLayoutData(currentNode);
                layoutData.setLayer(currentDepth);
                layoutData.setSlot(slot);
                slot++;
                EdgeIterable outEdges = diGraph.getOutEdges(currentNode);
                for (Edge outEdge : outEdges) {
                    Node successorNode = diGraph.getOpposite(currentNode, outEdge);
                    DagLayoutData successorLayoutData = getLayoutData(successorNode);
                    successorLayoutData.resolvePredecessor();
                    //avoid loops
                    if (successorLayoutData.predecessorsResolved()) {
                        nextLayerNodes.add(successorNode);
                    }
                }
            }

            if (slot > maxSlot) {
                maxSlot = slot;
            }
            currentLayerNodes = nextLayerNodes;
            currentDepth++;
        }

        //create grid with layers of slots
        grid = new Node[currentDepth][maxSlot];

        //register nodes in grid
        for (Node node : diGraph.getNodes()) {
            DagLayoutData layoutData = getLayoutData(node);
            int layer = layoutData.getLayer();
            int slot = layoutData.getSlot();
            grid[layer][slot] = node;
        }

        randomize();

        diGraph.readUnlock();

    }

    private LinkedList<Node> findRoots(DirectedGraph diGraph) {
        //roots are nodes with no predecessor. 
        LinkedList<Node> rootNodes = new LinkedList<Node>();
        for (Node node : diGraph.getNodes()) {
            int inDegree = diGraph.getInDegree(node);
            if (inDegree == 0) {
                rootNodes.add(node);
            }
        }
        return rootNodes;
    }

    private void randomize() {
        DirectedGraph diGraph = graphModel.getDirectedGraph();

        //for each node
        for (Node node : diGraph.getNodes()) {
            //find random slot in layer and switch position with it
            final DagLayoutData layoutData = getLayoutData(node);
            int slotIndex = layoutData.getSlot();
            int randomSlotIndex = (int) Math.floor(Math.random() * maxSlot);
            switchSlotNodes(layoutData.getLayer(), slotIndex, randomSlotIndex);
        }

    }

    private void fixLoops(DirectedGraph diGraph) {

        //find root nodes, which are the initial resolvable nodes
        List<Node> rootNodes = findRoots(diGraph);
        List<Node> resolvableNodes = rootNodes;

        //create a list of all nodes. resolved nodes will be removed. all nodes 
        //which are left after this are in loops
        List<Node> unresolvedNodes = new LinkedList<Node>();
        for (Node eachNode : diGraph.getNodes()) {
            unresolvedNodes.add(eachNode);
        }

        //repeat until all nodes are resolved
        while (!unresolvedNodes.isEmpty()) {

            //resolve as many nodes as possible
            while (!resolvableNodes.isEmpty()) {
                //two lists are needed here to prevent concurrent modification errors
                List<Node> nextResolvableNodes = new LinkedList<Node>();

                for (Node resolvableNode : resolvableNodes) {
                    DagLayoutData layoutData = getLayoutData(resolvableNode);
                    EdgeIterable outEdges = diGraph.getOutEdges(resolvableNode);
                    for (Edge outEdge : outEdges) {
                        Node successorNode = diGraph.getOpposite(resolvableNode, outEdge);
                        DagLayoutData successorLayoutData = getLayoutData(successorNode);
                        successorLayoutData.resolvePredecessor();
                        //avoid loops
                        if (successorLayoutData.predecessorsResolved()) {
                            nextResolvableNodes.add(successorNode);
                        }
                    }
                    unresolvedNodes.remove(resolvableNode);
                }
                resolvableNodes = nextResolvableNodes;
            }
            //if there are nodes left, there is a least one loop
            if (!unresolvedNodes.isEmpty()) {
                //it may be enough to remove one edge to resolve a couple of nodes
                //"remove" (or better: ignore) one edge at a time
                Node firstNode = unresolvedNodes.get(0);
                DagLayoutData layoutData = getLayoutData(firstNode);
                layoutData.ignoreLoopEdge();
                unresolvedNodes.remove(firstNode);
            }
        }

        //reset the number of unresolved predecessors to the number of predecessors
        resetUnresolvedInDegree(diGraph);

    }

    @Override
    public void goAlgo() {
        DirectedGraph diGraph = graphModel.getDirectedGraph();

        diGraph.readLock();

        improveSlots(diGraph, randomizationsPerPass);

        NodeIterable nodes = diGraph.getNodes();
        for (Node node : nodes) {

            float endX;
            float endY;

            DagLayoutData layoutData = getLayoutData(node);

            int layer = layoutData.getLayer();
            int slot = layoutData.getSlot();

            endX = slot * xDistance;
            endY = layer * yDistance * -1;

            float currentX = node.x();
            float currentY = node.y();
            float factor = speed;
            float nextX = endX * factor + currentX * (1 - factor);
            float nextY = endY * factor + currentY * (1 - factor);
            node.setX(nextX);
            node.setY(nextY);

        }

        diGraph.readUnlock();
    }

    private void improveSlots(DirectedGraph diGraph, int nodesToCheck) {
        for (int i = 0; i < nodesToCheck; i++) {
            improveSlots(diGraph);
        }
    }

    private void improveSlots(DirectedGraph diGraph) {
        //take random node
        int nodeCount = diGraph.getNodeCount();
        int randomNodeIndex = (int) Math.floor(Math.random() * nodeCount) + 1;
        Node randomNode = diGraph.getNode(randomNodeIndex);
        if (randomNode == null) {
            return;
        }
        int layer = getLayoutData(randomNode).getLayer();
        int slotIndex = getLayoutData(randomNode).getSlot();

        //take other random node from same layer or free spot
        int randomSlotIndex = (int) Math.floor(Math.random() * maxSlot);
        Node randomOtherNode = grid[layer][randomSlotIndex];

        //sum up distances of all edges of first node (in and out)
        double distanceSum = getDistanceSum(randomNode);

        //sum up distances of all edges of second node (in and out) 
        double otherDistanceSum = getDistanceSum(randomOtherNode);

        //sum up distances of all edges of first node if nodes where switched (in and out)
        double switchedDistanceSum = getSwitchedDistanceSum(randomNode, randomSlotIndex);

        //sum up distances of all edges of second node if nodes where switched (in and out) (0 if free spot)
        double otherSwitchedDistanceSum = getSwitchedDistanceSum(randomOtherNode, slotIndex);


        //calculate improvment factor of first node ((oldSum - newSum)/oldSum)
        double improvement = 0;
        if (distanceSum != 0) {
            improvement = (distanceSum - switchedDistanceSum) / distanceSum;
        }

        //calculate improvment factor of second node (0 if free spot)
        double otherImprovement = 0;
        if (otherDistanceSum != 0) {
            otherImprovement = (otherDistanceSum - otherSwitchedDistanceSum) / otherDistanceSum;
        }

        //if sum of improvements > 0 then switch x positions
        if (improvement + otherImprovement > 0) {

            switchSlotNodes(layer, slotIndex, randomSlotIndex);
        }
    }

    private void switchSlotNodes(int layer, int firstSlot, int secondSlot) {

        Node firstNode = grid[layer][firstSlot];
        Node secondNode = grid[layer][secondSlot];

        grid[layer][firstSlot] = secondNode;
        grid[layer][secondSlot] = firstNode;

        if (firstNode != null) {
            getLayoutData(firstNode).setSlot(secondSlot);
        }
        if (secondNode != null) {
            getLayoutData(secondNode).setSlot(firstSlot);
        }

    }

    @Override
    public void endAlgo() {
        executing = false;
    }

    @Override
    public boolean canAlgo() {
        return executing;
    }

    @Override
    public LayoutProperty[] getProperties() {
        List<LayoutProperty> properties = new ArrayList<LayoutProperty>();
        final String DAGLAYOUT = "DAG Layout";

        try {
            properties.add(LayoutProperty.createProperty(
                    this, Integer.class,
                    "X Distance",
                    DAGLAYOUT,
                    "The x distance between layers",
                    "getXDistance", "setXDistance"));
            properties.add(LayoutProperty.createProperty(
                    this, Integer.class,
                    "Y Distance",
                    DAGLAYOUT,
                    "The y distance between layers",
                    "getYDistance", "setYDistance"));
            properties.add(LayoutProperty.createProperty(
                    this, Float.class,
                    "Speed",
                    DAGLAYOUT,
                    "Animation Speed Factor between 0 and 1",
                    "getSpeed", "setSpeed"));
            properties.add(LayoutProperty.createProperty(
                    this, Integer.class,
                    "Random Optimizations",
                    DAGLAYOUT,
                    "Random Optimizations per Pass",
                    "getRandomizationsPerPass", "setRandomizationsPerPass"));
        } catch (Exception e) {
            e.printStackTrace();
        }

        return properties.toArray(
                new LayoutProperty[0]);
    }

    @Override
    public LayoutBuilder getBuilder() {
        return builder;
    }

    @Override
    public void setGraphModel(GraphModel gm) {
        this.graphModel = gm;
    }

    public Float getSpeed() {
        return speed;
    }

    public void setSpeed(Float speed) {
        this.speed = speed;
    }

    public Integer getXDistance() {
        return xDistance;
    }

    public void setXDistance(Integer xDistance) {
        this.xDistance = xDistance;
    }

    public Integer getYDistance() {
        return yDistance;
    }

    public void setYDistance(Integer yDistance) {
        this.yDistance = yDistance;
    }

    public void setRandomizationsPerPass(Integer randomizationsPerPass) {
        this.randomizationsPerPass = randomizationsPerPass;
    }

    public Integer getRandomizationsPerPass() {
        return randomizationsPerPass;
    }

    private DagLayoutData getLayoutData(Node node) {
        if (node == null) {
            return null;
        }

        return (DagLayoutData) node.getLayoutData();
    }

    private double getDistanceSum(Node node) {
        if (node == null) {
            return 0;
        }
        int slot = getLayoutData(node).getSlot();
        return getSwitchedDistanceSum(node, slot);
    }

    private double getSwitchedDistanceSum(Node node, int newSlot) {
        if (node == null) {
            return 0;
        }
        double sum = 0;

        DagLayoutData layoutData = getLayoutData(node);
        int x = newSlot;
        int y = layoutData.getLayer();

        DirectedGraph diGraph = graphModel.getDirectedGraph();
        EdgeIterable edges = diGraph.getEdges(node);
        for (Edge edge : edges) {
            Node otherNode = diGraph.getOpposite(node, edge);
            DagLayoutData otherLayoutData = getLayoutData(otherNode);
            int otherX = otherLayoutData.getSlot();
            int otherY = otherLayoutData.getLayer();
            int dx = x - otherX;
            int dy = y - otherY;

            double dist = Math.sqrt(dx * dx + dy * dy);
            sum += dist;
        }
        return sum;

    }

    private void initLayoutData(DirectedGraph diGraph) {
        //init layoutData
        for (Node node : diGraph.getNodes()) {
            DagLayoutData layoutData = new DagLayoutData();

            layoutData.setLayer(0);

            node.setLayoutData(layoutData);
        }
        resetUnresolvedInDegree(diGraph);
    }

    private void resetUnresolvedInDegree(DirectedGraph diGraph) {
        for (Node node : diGraph.getNodes()) {
            DagLayoutData layoutData = getLayoutData(node);

            //set the number of nodes which have to be assigned to a layer 
            //before this one. layers will be assigned top down, so all 
            // predecessors have to be assigned, which are inDegree many.
            layoutData.setUnresolvedInDegree(diGraph.getInDegree(node));

            node.setLayoutData(layoutData);
        }
    }
}
