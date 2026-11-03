package core;

/** Behaviour when the bounded task queue has reached capacity. */
public enum SubmissionPolicy {
    /** Wait until a worker removes an item and frees a slot. */
    BLOCK,
    /** Fail immediately if the queue is full. */
    REJECT,
    /** Wait up to the supplied timeout, then fail. */
    TIMEOUT
}
