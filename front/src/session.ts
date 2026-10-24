/** What a tab remembers to get its seat back after a reload or a lost connection. */
export interface Session {
  code: string;
  token: string;
}

export interface SessionStore {
  load(): Session | null;
  save(session: Session): void;
  clear(): void;
}

const KEY = "semaphore.session";

/**
 * Per tab, on purpose: two tabs of one browser are two players, so they must not share a token.
 * Storage can be unavailable (private mode, blocked): the game then just does not survive a reload.
 */
export const browserSessionStore: SessionStore = {
  load() {
    try {
      const raw = sessionStorage.getItem(KEY);
      if (raw === null) {
        return null;
      }
      const { code, token } = JSON.parse(raw) as Partial<Session>;
      return typeof code === "string" && typeof token === "string"
        ? { code, token }
        : null;
    } catch {
      return null;
    }
  },
  save(session) {
    try {
      sessionStorage.setItem(KEY, JSON.stringify(session));
    } catch {
      // see above
    }
  },
  clear() {
    try {
      sessionStorage.removeItem(KEY);
    } catch {
      // see above
    }
  },
};
