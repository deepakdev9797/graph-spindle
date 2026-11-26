package io.graphspindle.ingestion.ops;

/**
 * One typed operation proposed by the reconcile stage (ARCHITECTURE §4.2). The LLM only ever
 * <em>proposes</em> these; {@code OperationValidator} decides what is applied.
 */
public sealed interface MutationOperation
        permits CreateEntity, UpdateEntity, AddAlias, AssertClaim, SupersedeClaim, RetractClaim, FlagConflict,
        ProposeMerge, LinkSource {

    /** The snake_case wire discriminator ({@code op}). */
    String wireName();
}
