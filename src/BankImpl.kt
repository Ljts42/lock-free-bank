import kotlinx.atomicfu.*

/**
 * Bank implementation.
 * This class is thread-safe and lock-free using operation objects.
 *
 * This implementation is based on "A Practical Multi-Word Compare-and-Swap Operation" by T. L. Harris et al.
 * It uses a simplified and faster version of DCSS operation that relies for its correctness on the fact that
 * Account instances in [accounts] array never suffer from ABA problem.
 * See also "Practical lock-freedom" by Keir Fraser. See [acquire] method.
 *
 * @author Sentemov Lev
 */
class BankImpl(override val numberOfAccounts: Int) : Bank {
    /**
     * An array of accounts.
     * Account instances here are never reused (there is no ABA).
     */
    private val accounts = atomicArrayOfNulls<Account>(numberOfAccounts)

    init { for (i in 0 until numberOfAccounts) accounts[i].value = Account(0) }

    private fun account(index: Int) = accounts[index].value!!

    override fun getAmount(index: Int): Long {
        while (true) {
            val account = account(index)
            /*
             * If there is a pending operation on this account, then help to complete it first using
             * its invokeOperation method. If the result is false then there is no pending operation,
             * thus the account amount can be safely returned.
             */
            if (!account.invokeOperation()) return account.amount
        }
    }

    override val totalAmount: Long
        get() {
            /**
             * This operation requires atomic read of all accounts, thus it creates an operation descriptor.
             * Operation's invokeOperation method acquires all accounts, computes the total amount, and releases
             * all accounts. This method returns the result.
             */
            val op = TotalAmountOp()
            op.invokeOperation()
            return op.sum
        }

    override fun deposit(index: Int, amount: Long): Long { // First, validate method per-conditions
        require(amount > 0) { "Invalid amount: $amount" }
        check(amount <= MAX_AMOUNT) { "Overflow" }
        /*
         * This operation depends only on a single account, thus it can be directly
         * performed using a regular lock-free compareAndSet loop.
         */
        while (true) {
            val account = account(index)
            /*
             * If there is a pending operation on this account, then help to complete it first using
             * its invokeOperation method. If the result is false then there is no pending operation,
             * thus the account can be safely updated.
             */
            if (account.invokeOperation()) continue
            check(account.amount + amount <= MAX_AMOUNT) { "Overflow" }
            val updated = Account(account.amount + amount)
            if (accounts[index].compareAndSet(account, updated)) return updated.amount
        }
    }

    override fun withdraw(index: Int, amount: Long): Long {
        require(amount > 0) { "Invalid amount: $amount" }
        check(amount <= MAX_AMOUNT) { "Overflow" }
        /*
         * This operation depends only on a single account, thus it can be directly
         * performed using a regular lock-free compareAndSet loop.
         */
        while (true) {
            val account = account(index)
            /*
             * If there is a pending operation on this account, then help to complete it first using
             * its invokeOperation method. If the result is false then there is no pending operation,
             * thus the account can be safely updated.
             */
            if (account.invokeOperation()) continue
            check(account.amount - amount >= 0) { "Underflow" }
            val updated = Account(account.amount - amount)
            if (accounts[index].compareAndSet(account, updated)) return updated.amount
        }
    }

    override fun transfer(fromIndex: Int, toIndex: Int, amount: Long) {
        // First, validate method per-conditions
        require(amount > 0) { "Invalid amount: $amount" }
        require(fromIndex != toIndex) { "fromIndex == toIndex" }
        check(amount <= MAX_AMOUNT) { "Underflow/overflow" }
        /**
         * This operation requires atomic read of two accounts, thus it creates an operation descriptor.
         * Operation's invokeOperation method acquires both accounts, computes the result of operation
         * (if a form of error message), and releases both accounts. This method throws the exception with
         * the corresponding message if needed.
         */
        val op = TransferOp(fromIndex, toIndex, amount)
        op.invokeOperation()
        op.errorMessage?.let { error(it) }
    }

    /**
     * This is an implementation of a restricted form of Harris DCSS operation:
     * It atomically checks that op.completed is false and replaces accounts[index] with AcquiredAccount instance
     * that hold a reference to the op.
     * This method returns null if op.completed is true.
     */
    private fun acquire(index: Int, op: Op): AcquiredAccount? {
        while (true) {
            val account = account(index)
            if (op.completed) return null
            if (account is AcquiredAccount) {
                if (account.op != op) {
                    if (account.invokeOperation()) continue
                } else {
                    return account
                }
            }
            val updated = AcquiredAccount(account.amount, op)
            if (accounts[index].compareAndSet(account, updated)) return updated
        }
    }

    /**
     * Releases an account that was previously acquired by [acquire].
     * This method does nothing if the account at index is not currently acquired.
     */
    private fun release(index: Int, op: Op) {
        assert(op.completed) // must be called only on operations that were already completed
        val account = account(index)
        if (account is AcquiredAccount && account.op === op) {
            // release performs update at most once while the account is still acquired
            val updated = Account(account.newAmount)
            accounts[index].compareAndSet(account, updated)
        }
    }

    /**
     * Immutable account data structure.
     * @param amount Amount of funds in this account.
     */
    private open class Account(val amount: Long) {
        /**
         * Invokes operation that is pending on this account.
         * This implementation returns false (no pending operation), other implementations return true.
         */
        open fun invokeOperation(): Boolean = false
    }

    /**
     * Account that was acquired as a part of in-progress operation that spans multiple accounts.
     * @see acquire
     */
    private class AcquiredAccount(
        var newAmount: Long, // New amount of funds in this account when op completes.
        val op: Op
    ) : Account(newAmount) {
        override fun invokeOperation(): Boolean {
            op.invokeOperation()
            return true
        }
    }

    /**
     * Abstract operation that acts on multiple accounts.
     */
    private abstract inner class Op {
        /**
         * True when operation has completed.
         */
        @Volatile
        var completed = false

        abstract fun invokeOperation()
    }

    /**
     * Descriptor for [totalAmount] operation.
     */
    private inner class TotalAmountOp : Op() {
        /**
         * The result of getTotalAmount operation is stored here before setting
         * [completed] to true.
         */
        var sum = 0L

        override fun invokeOperation() {
            var sum = 0L
            var acquired = 0
            while (acquired < numberOfAccounts) {
                val account = acquire(acquired, this) ?: break
                sum += account.newAmount
                acquired++
            }
            if (acquired == numberOfAccounts) {
                /*
                 * If i == n, then all acquired accounts were not null and full sum was calculated.
                 * this.sum = sum assignment below has a benign data race. Multiple threads might to this assignment
                 * concurrently, however, they are all guaranteed to be assigning the same value.
                 */
                this.sum = sum
                completed = true // volatile write to completed field _after_ the sum was written
            }
            /*
             * To ensure lock-freedom, we must release all accounts even if this particular helper operation
             * had failed to acquire all of them before somebody else had completed the operations.
             * By releasing all accounts for completed operation we ensure progress of other operations.
             */
            for (i in 0 until numberOfAccounts) {
                release(i, this)
            }
        }
    }

    /**
     * Descriptor for [transfer] operation.
     */
    private inner class TransferOp(val fromIndex: Int, val toIndex: Int, val amount: Long) : Op() {
        var errorMessage: String? = null

        override fun invokeOperation() {
            require(amount > 0) { "Invalid amount: $amount" }
            require(fromIndex != toIndex) { "fromIndex == toIndex" }

            val from: AcquiredAccount?
            val to: AcquiredAccount?
            if (fromIndex < toIndex) {
                from = acquire(fromIndex, this)
                to = acquire(toIndex, this)
            } else {
                to = acquire(toIndex, this)
                from = acquire(fromIndex, this)
            }
            if (from != null && to != null) {
                when {
                    amount > from.amount -> errorMessage = "Underflow"
                    to.amount + amount > MAX_AMOUNT -> errorMessage = "Overflow"
                    else -> {
                        from.newAmount = from.amount - amount
                        to.newAmount = to.amount + amount
                    }
                }
            }
            completed = true
            if (fromIndex < toIndex) {
                release(toIndex, this)
                release(fromIndex, this)
            } else {
                release(fromIndex, this)
                release(toIndex, this)
            }
        }
    }
}