import { useState } from "react";

export type Motion = "enter" | "leave";

interface Leaver<T> {
  item: T;
  index: number;
}

/**
 * Whether the browser may move things. When it may not, nothing is marked and no row is kept for an exit that would
 * never play: the screen is final at once, as it always is for the tests.
 */
const motionAllowed = () =>
  window.matchMedia("(prefers-reduced-motion: no-preference)").matches;

/**
 * What entered `items` since the render before, and what left it, so that a list animates a change and not its first
 * render: a card already revealed, or a player already in the room, when the page opens (join, rejoin, spectator)
 * is never marked. `present` says when an item counts, so a card enters when it is revealed.
 *
 * It only tells CSS what to play, the state itself is never delayed. `motionOf` is "enter" for an item that came in,
 * "leave" for one that went out and is kept in `shown` (where it was) so that it can fade out; both last until the
 * CSS animation ends and the element calls `settle`.
 */
export const useListMotion = <T>(
  items: readonly T[],
  id: (item: T) => string,
  present: (item: T) => boolean = () => true,
) => {
  const [seen, setSeen] = useState(items);
  const [entering, setEntering] = useState<ReadonlySet<string>>(new Set());
  const [leavers, setLeavers] = useState<ReadonlyMap<string, Leaver<T>>>(
    new Map(),
  );

  // Derived from the previous list during the render, the way React asks for it, rather than from an effect.
  if (items !== seen) {
    setSeen(items);
    if (motionAllowed()) {
      const now = new Set(items.filter(present).map(id));
      const before = new Set(seen.filter(present).map(id));
      const entered = new Set(entering);
      const left = new Map(leavers);
      for (const key of now) {
        if (!before.has(key)) {
          entered.add(key);
        }
        left.delete(key);
      }
      seen.forEach((item, index) => {
        if (present(item) && !now.has(id(item))) {
          left.set(id(item), { item, index });
        }
      });
      setEntering(entered);
      setLeavers(left);
    }
  }

  const shown = [...items];
  for (const { item, index } of [...leavers.values()].sort(
    (a, b) => a.index - b.index,
  )) {
    shown.splice(index, 0, item);
  }

  const motionOf = (key: string): Motion | undefined => {
    if (leavers.has(key)) {
      return "leave";
    }
    return entering.has(key) ? "enter" : undefined;
  };

  const settle = (key: string) => {
    setEntering(
      (set) => new Set([...set].filter((entered) => entered !== key)),
    );
    setLeavers((map) => new Map([...map].filter(([left]) => left !== key)));
  };

  return { shown, motionOf, settle };
};
