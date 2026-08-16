/**
 * Test setup — polyfills that the browser provides and Node does not:
 * - fake-indexeddb so the Dexie schema/repository/vault tests run for real.
 * - Node's WebCrypto is global in Node 24; ensure it exists for the vault tests.
 */
import "fake-indexeddb/auto";
