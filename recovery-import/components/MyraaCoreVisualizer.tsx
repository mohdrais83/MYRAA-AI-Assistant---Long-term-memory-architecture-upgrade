import React from "react";

export type MyraaEmotion = "neutral" | "happy" | "thinking" | "listening" | "speaking";

export function MyraaCoreVisualizer({ emotion = "neutral" }: { emotion?: MyraaEmotion }) {
  return (
    <div style={{ display: "grid", placeItems: "center", padding: 24 }}>
      <div style={{
        width: 150, height: 150, borderRadius: "50%",
        background: "radial-gradient(circle, #67e8f9, #2563eb 65%, #111827)",
        boxShadow: "0 0 45px #38bdf8",
        animation: "myraaPulse 2s ease-in-out infinite"
      }} />
      <style>{`@keyframes myraaPulse { 50% { transform: scale(1.08); opacity: .75; } }`}</style>
      <p>MYRAA · {emotion}</p>
    </div>
  );
}
