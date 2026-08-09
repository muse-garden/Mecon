package com.mecon.api.config

/**
 * Global configuration parameters for the Mecon engine.
 */
object MeconConfig {
    
    /**
     * Configuration parameters for collection data structures.
     */
    object Collection {
        /**
         * The default order (maximum branching factor) for BPlusTree.
         * A higher order typically results in a shallower tree, reducing object allocations
         * and memory locality during search at the cost of slightly more time per node split.
         */
        const val BPLUS_TREE_DEFAULT_ORDER = 16
    }
}
