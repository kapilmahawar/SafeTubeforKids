package tv.safetubeforkids.app.auth

/**
 * The Parent PIN the tests use.
 *
 * One constant rather than a literal in each suite: since W10 a PIN is *chosen*, so a test has to
 * state one, and a single well-known value makes a failing assertion readable.
 */
const val TEST_PIN = "482913"

/**
 * The production hasher with a test-sized iteration count.
 *
 * PBKDF2 is deliberately slow - that is what it is for - and the suites invoke it hundreds of times.
 * The cost itself is pinned by `PinManagerTest.theDefaultHasherIsRealPbkdf2WithItsOwnSalt`, which uses
 * the real 120,000 rounds, so lowering it here does not weaken what is verified; it only keeps the
 * suite from spending minutes proving the same thing.
 */
val TestHasher: SecretHasher = Pbkdf2SecretHasher(iterations = 1_000)

/** A configured credential over an in-memory store, for the suites that need one to exist. */
fun testPinManager(
    store: ParentCredentialStore = InMemoryParentCredentialStore(),
    clock: () -> Long = System::currentTimeMillis,
    onPinValidated: ((String) -> String)? = null,
    onCredentialsChanged: (() -> Unit)? = null,
    pinLockoutPersistence: PinLockoutPersistence? = null,
    recoveryLockoutPersistence: PinLockoutPersistence? = null,
    pin: String = TEST_PIN,
): PinManager {
    val manager = PinManager(
        store = store,
        clock = clock,
        onPinValidated = onPinValidated,
        onCredentialsChanged = onCredentialsChanged,
        hasher = TestHasher,
        pinLockoutPersistence = pinLockoutPersistence,
        recoveryLockoutPersistence = recoveryLockoutPersistence,
    )
    manager.setup(pin, pin)
    return manager
}
