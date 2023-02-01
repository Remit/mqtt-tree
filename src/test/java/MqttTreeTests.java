/*
 * Copyright (c) 2021 Simon Johnson <simon622 AT gmail DOT com>
 *
 * Find me on GitHub:
 * https://github.com/simon622
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slj.mqtt.tree.MqttTree;
import org.slj.mqtt.tree.MqttTreeException;
import org.slj.mqtt.tree.MqttTreeLimitExceededException;
import org.slj.mqtt.tree.ui.MqttTreeViewer;

import javax.swing.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class MqttTreeTests {

    @Before
    public void setup(){
    }

    @Test
    public void testReadAllFromTree() throws MqttTreeException, MqttTreeLimitExceededException {
        MqttTree<String> tree = createTreeDefaultConfig();
        int BRANCHES = 50;
        Set<String> added = new HashSet<>();
        for (int i = 0; i < BRANCHES; i++){
            int depth = ThreadLocalRandom.current().nextInt(1, 10);
            String topic = generateRandomTopic(depth);
            tree.addSubscription(topic, "foo");
            added.add(topic);
        }
        Set<String> all = tree.getDistinctPaths(true);
        Assert.assertEquals("all distinct topics should be returned by match", added.size(), all.size());
        all.removeAll(added);
        Assert.assertEquals("all topics should exist in both withs", 0, all.size());
    }

    @Test(expected = MqttTreeLimitExceededException.class)
    public void testLargeTopicExceedsMaxSegments() throws MqttTreeException, MqttTreeLimitExceededException {
        MqttTree<String> tree = createTreeDefaultConfig();
        String topic = generateRandomTopic((int) tree.getMaxPathSegments() + 1);
        tree.addSubscription(topic, "foo");
    }

    @Test(expected = MqttTreeLimitExceededException.class)
    public void testLargeTopicExceedsMaxLength() throws MqttTreeException, MqttTreeLimitExceededException {
        MqttTree<String> tree = createTreeDefaultConfig();
        String topic = generateTopicMaxLength((int) tree.getMaxPathSize() + 1);
        tree.addSubscription(topic, "foo");
    }

    @Test(expected = MqttTreeLimitExceededException.class)
    public void testLargeTopicExceedsMaxMembers() throws MqttTreeException, MqttTreeLimitExceededException {
        MqttTree<String> tree = createTreeDefaultConfig();
        String topic = generateRandomTopic(10);
        String[] members = new String[(int) tree.getMaxMembersAtLevel() + 1];
        Arrays.fill(members, UUID.randomUUID().toString());
        tree.addSubscription(topic, members);
    }

//    @Test
    public void testConcurrency() throws Exception {

        MqttTree<String> tree = new MqttTree<>(MqttTree.DEFAULT_SPLIT, true);
        tree.withWildcard("#");
        tree.withWildpath("+");
        tree.withMaxMembersAtLevel(1000000);

        int loops = 100;
        int threads = 100;
        CountDownLatch latch = new CountDownLatch(loops * threads);
        final long start = System.currentTimeMillis();
        AtomicInteger c = new AtomicInteger();

        //read thread
        Thread readThread = new Thread(new Runnable() {
            @Override
            public void run() {
               while(true){
                   try {
                       long start1 = System.currentTimeMillis();
                       Set<String> s = tree.search("some/topic/1");
                       Iterator<String> itr = s.iterator();
                       int i = 0;
                       while(itr.hasNext()){
                           itr.next();
                           i++;
                       }
                       long t = System.currentTimeMillis() - start;
                       if(t > 250){
//                           Thread.sleep(1000);
//                            System.err.println("read ["+i+"] took " + t + "ms for " + s.size());
                       }

                   } catch(Exception e){
                       e.printStackTrace();
                       Assert.fail(e.getMessage());
                   }
                }
            }
        });
        readThread.start();

        AtomicInteger added = new AtomicInteger();
        AtomicInteger removed = new AtomicInteger();
        AtomicInteger total = new AtomicInteger();

        final Set<String> allAdded = Collections.synchronizedSet(new HashSet());
        final Set<String> allRemoved = Collections.synchronizedSet(new HashSet());

        for(int t = 0;  t < threads; t++){
            Thread tt = new Thread(() -> {
                for (int i = 0; i < loops; i++){
                    try {
                        if(i % 2 == 0){
                            String subscriberId = ""+c.incrementAndGet();
                            tree.addSubscription("some/topic/1",subscriberId);
                            for (int j = 0; j < 100; j++){
                                tree.addSubscription(generateRandomTopic(ThreadLocalRandom.current().nextInt(2, 40)),subscriberId);
                            }
                            added.incrementAndGet();
                            allAdded.add(subscriberId);
//                            tree.addSubscription("#",""+ThreadLocalRandom.current().nextInt(10, 100000));
                        } else {

                            String subId = c.get() + "";
                            if(tree.removeSubscriptionFromPath("some/topic/1", subId)){
                                removed.incrementAndGet();
                                allRemoved.add(subId);
                            }
                        }
                    } catch(Exception e){
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                        total.incrementAndGet();
                    }
                }
            });
            tt.start();
        }

        latch.await();

        long quickstart = System.currentTimeMillis();
        Set<String> s = tree.search("some/topic/1");
        long size = s.size();
        long done = System.currentTimeMillis();
        s.retainAll(allRemoved);
        Assert.assertEquals("no removed members should exists in search", 0, s.size());

        System.out.println("Read Took: " + (done - quickstart) + "ms for ["+size+"] items");
        System.out.println("Write Took: " + (System.currentTimeMillis() - start) + "ms");
        System.out.println("Root Branches: "+ tree.getBranchCount());
        System.out.println("Total Branches: "+ tree.countDistinctPaths(false));
        System.out.println("Read Took: "+ (done - quickstart));
    }

    @Test
    public void testTopLevelTokenMatch() throws MqttTreeException, MqttTreeLimitExceededException {

        MqttTree<String> tree = createTreeDefaultConfig();
        tree.addSubscription("/", "foo");
        Assert.assertEquals("first level is a token", 1, tree.search("/").size());
    }

    @Test
    public void testTopLevelPrefixTokenMatchDistinct() throws MqttTreeException, MqttTreeLimitExceededException {

        MqttTree<String> tree = createTreeDefaultConfig();
        tree.addSubscription("/foo", "foo"); //different things
        tree.addSubscription("foo", "bar");
        System.err.println(tree.toTree(System.lineSeparator()));
        Assert.assertEquals("should be 2 distinct branches", 2, tree.getBranchCount());
        Assert.assertEquals("top level path sep is distinct from none", 1, tree.search("/foo").size());
        Assert.assertEquals("top level path sep is distinct from none", 1, tree.search("foo").size());
    }

    @Test
    public void testTopLevelSuffixTokenMatchDistinct() throws MqttTreeException, MqttTreeLimitExceededException {

        MqttTree<String> tree = createTreeDefaultConfig();
        tree.addSubscription("foo/", "foo"); //different things
        tree.addSubscription("foo", "bar");
        System.err.println(tree.toTree(System.lineSeparator()));
        Assert.assertEquals("should be 2 distinct branches", 1, tree.getBranchCount());
        Assert.assertEquals("top level path sep is distinct from none", 1, tree.search("foo/").size());
        Assert.assertEquals("top level path sep is distinct from none", 1, tree.search("foo").size());
        Assert.assertEquals("top level path sep is distinct from none", 0, tree.search("/foo").size());
    }

    @Test
    public void testWildcard() throws MqttTreeException, MqttTreeLimitExceededException {

        MqttTree<String> tree = createTreeDefaultConfig();
        tree.addSubscription("foo/bar/#", "foo");
        tree.addSubscription("foo/#", "bar");
        tree.addSubscription("/#", "foo1");
        tree.addSubscription("#", "root");
        System.err.println(tree.toTree(System.lineSeparator()));
        Assert.assertEquals("should be 1 distinct branches", 3, tree.getBranchCount());
        Assert.assertEquals("wildcard should match", 3, tree.search("foo/bar/is/me").size());
        Assert.assertEquals("wildcard should match", 2, tree.search("/foo/bar/is/you").size());
        Assert.assertEquals("wildcard should match", 2, tree.search("foo/bar").size());
        Assert.assertEquals("wildcard should match", 1, tree.search("moo/bar").size());
    }

    @Test
    public void testWildpath() throws MqttTreeException, MqttTreeLimitExceededException {

        MqttTree<String> tree = createTreeDefaultConfig();
        tree.addSubscription("foo/+/is/good", "foo");
        System.err.println(tree.toTree(System.lineSeparator()));
        Assert.assertEquals("should be 1 distinct branches", 1, tree.getBranchCount());
        Assert.assertEquals("wildcard should match", 0, tree.search("foo/bar").size());
        Assert.assertEquals("wildcard should match", 1, tree.search("foo/mar/is/good").size());
        Assert.assertEquals("wildcard should match", 1, tree.search("foo/bar/is/good").size());
        Assert.assertEquals("wildcard should match", 1, tree.search("foo/ /is/good").size());
        Assert.assertEquals("wildcard should match", 0, tree.search("foo/bar/is/good/or/bad").size());
        Assert.assertEquals("wildcard should match", 0, tree.search("foo/bar/is/bad").size());
        Assert.assertEquals("wildcard should match", 0, tree.search("/foo/bar/is/good").size());
        Assert.assertEquals("wildcard should match", 0, tree.search("foo/bar/is/bad").size());
    }

    @Test
    public void testWildcardsGetRolledUp() throws MqttTreeException, MqttTreeLimitExceededException {

        MqttTree<String> tree = createTreeDefaultConfig();

        tree.addSubscription("foo/#", "foo");
        tree.addSubscription("foo/bar", "foo1");
        tree.addSubscription("foo/bar/foo", "foo2");
        tree.addSubscription("foo/bar/zoo", "foo3");
        tree.addSubscription("foo/bar/#", "foo4");
        System.err.println(tree.toTree(System.lineSeparator()));

        Assert.assertEquals("should be 1 distinct branches", 1, tree.getBranchCount());
        Assert.assertEquals("wildcard should match", 3, tree.search("foo/bar/zoo").size());
    }

    @Test
    public void testPathExistenceInBigTree() throws MqttTreeException, MqttTreeLimitExceededException {

        MqttTree<Integer> tree = new MqttTree<>(MqttTree.DEFAULT_SPLIT, true);
        String search = "some/member";
        String searchNoMem = "/some/member";

        //-- rememeber the / is a token
        tree.withMaxPathSegments(tree.getMaxPathSegments() * 2 + 1);
        tree.withMaxPathSize(1024 * 4);

        for (int i = 0; i < 200_000_0; i++){
            String topic = generateRandomTopic(5);
            if(i % 2 == 0){
                tree.addSubscription(topic,
                        ThreadLocalRandom.current().nextInt(0, 1000));
            } else {
                tree.addSubscription(topic);
            }
        }

        tree.addSubscription(search,
                ThreadLocalRandom.current().nextInt(0, 1000));
        tree.addSubscription(searchNoMem);

//        System.err.println(tree.toTree(System.lineSeparator()));

        Assert.assertTrue("this path should exist", tree.hasPath(search));
        Assert.assertTrue("this path should exist", tree.hasPath(searchNoMem));

        Assert.assertTrue("this path should have members", tree.hasMembers(search));
        Assert.assertFalse("this path should not have members", tree.hasMembers(searchNoMem));

        Assert.assertFalse("this path should not not exist", tree.hasPath("/doesnt/exits"));
        Assert.assertFalse("this path should not not exist nor have members", tree.hasMembers("/doesnt/exits"));

        Assert.assertEquals("path count should match", 200_000_2, tree.countDistinctPaths(false));
    }

    public static String generateRandomTopic(int segments){
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments; i++){
            if(i == 0) sb.append("/");
            int r = ThreadLocalRandom.current().nextInt(0, 1000);
            sb.append(r);
            sb.append("/");
        }
        return sb.toString();
    }

    public static String generateTopicMaxLength(int length){
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++){
            sb.append("a");
        }
        return sb.toString();
    }

    protected static MqttTree<String> createTreeDefaultConfig(){
        MqttTree<String> tree = new MqttTree<>(MqttTree.DEFAULT_SPLIT, true);
        tree.withWildcard("#");
        tree.withWildpath("+");
        tree.withMaxMembersAtLevel(1000000);
        return tree;
    }
}
