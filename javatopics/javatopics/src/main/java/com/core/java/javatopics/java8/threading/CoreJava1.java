package com.core.java.javatopics.java8.threading;

//class parent1 implements  Runnable{
//
//    @Override
//    public void run() {
//        for(int i=0;i<10000;i++){
//            System.out.println("j="+i);
//            System.out.println(Thread.currentThread().getName());
//        }
//    }
//}

public class CoreJava1 {
    public static void main(String[] args) throws InterruptedException {
//        Thread t1 = new Thread(new parent1());
//        t1.start();
//        new parent1().run();
        Thread t2 = new Thread(()->{
            for(int i=0;i<10;i++){
                System.out.println("j="+i);
                System.out.println(Thread.currentThread().getName());
            }
        });
        Thread t3 = new Thread(()->{
            for(int i=0;i<10;i++){
                System.out.println("k="+i);
                System.out.println(Thread.currentThread().getName());
            }
        });
        t2.start();
        t3.start();
        for(int i=0;i<10;i++){
//            if(i==100)Thread.sleep(5000);
            System.out.println("i="+i);
            System.out.println(Thread.currentThread().getName());
        }


    }

}
