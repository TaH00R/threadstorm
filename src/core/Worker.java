package core;

public class Worker extends Thread{
    public Worker(String name){
        super(name);
    }

    @Override
    public void run() {
        System.out.println("Worker: " + getName() + " is ready");
    }
}
