import core.Worker;

public class Main {

    public static void main(String[] args) {

        Worker w1 = new Worker("Worker-1");
        Worker w2 = new Worker("Worker-2");
        Worker w3 = new Worker("Worker-3");

        w1.start();
        w2.start();
        w3.start();
    }
}