/**
 * Mock cloud service - the web mirror of MockCloudService.kt.
 *
 * Authentication is a subscribable store rather than a getter. Settings polled the
 * getter every two seconds, on both platforms, for a value that only ever changes
 * in signIn and signOut.
 */
export interface CloudService {
    pushVocabulary: (list: unknown[]) => Promise<boolean>;
    pullVocabulary: (since: number) => Promise<unknown[]>;
    signIn: (email: string, pass: string) => Promise<boolean>;
    signOut: () => void;
    isAuthenticated: () => boolean;
    subscribe: (listener: () => void) => () => void;
}

let loggedIn = false;
const listeners = new Set<() => void>();

function setLoggedIn(next: boolean) {
    if (loggedIn === next) return;
    loggedIn = next;
    for (const listener of listeners) listener();
}

export const mockCloudService: CloudService = {
    pushVocabulary: async () => true,
    pullVocabulary: async () => [],
    signIn: async () => {
        setLoggedIn(true);
        return true;
    },
    signOut: () => setLoggedIn(false),
    isAuthenticated: () => loggedIn,
    subscribe: (listener) => {
        listeners.add(listener);
        return () => {
            listeners.delete(listener);
        };
    },
};
