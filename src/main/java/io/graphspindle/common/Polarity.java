package io.graphspindle.common;

/** Claim polarity. NEGATIVE ("X does not use Y") is never projected (SCHEMA §5.4). */
public enum Polarity {
    POSITIVE,
    NEGATIVE
}
