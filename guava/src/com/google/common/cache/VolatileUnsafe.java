/*
 * Copyright (c) 2014 Qunar.com. All Rights Reserved.
 */
package com.google.common.cache;

/**
 * @author: ketao Date: 14/12/14 Time: 下午8:21
 * @version: \$Id$
 */
public class VolatileUnsafe {

    static volatile int COUNT = 0;

    public static void main(String[] args) {

        for (int i = 0; i < 100; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {

                    stopSecond(1);
                    COUNT += 100;
                }
            }).start();
        }

        stopSecond(1000);
        System.out.println("100 times increase sum is :" + COUNT);
    }

    private static void stopSecond(int a) {

        try {
            Thread.sleep(a * 10);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
