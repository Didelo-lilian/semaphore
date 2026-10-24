import type { CardKind, Team } from "../protocol.ts";

export const TEAM_NAME: Record<Team, string> = {
  BABORD: "Bâbord",
  TRIBORD: "Tribord",
};

/**
 * The two teams have almost the same luminance: they are told apart by shape, never by color alone
 * (ADR 0001). Bâbord is a triangle, Tribord a circle, calm waters a square, a reef a cross.
 */
export const TeamMark = ({
  kind,
  size = 18,
}: {
  kind: CardKind;
  size?: number;
}) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 18 18"
    aria-hidden="true"
    focusable="false"
    className="sem-mark"
    data-kind={kind}
  >
    {kind === "BABORD" && (
      <polygon points="9,2 17,16 1,16" fill="currentColor" />
    )}
    {kind === "TRIBORD" && <circle cx="9" cy="9" r="7.5" fill="currentColor" />}
    {kind === "CALM" && (
      <rect x="3" y="3" width="12" height="12" rx="2" fill="currentColor" />
    )}
    {kind === "REEF" && (
      <path
        d="M3 3L15 15M15 3L3 15"
        stroke="currentColor"
        strokeWidth="3"
        strokeLinecap="round"
      />
    )}
  </svg>
);
