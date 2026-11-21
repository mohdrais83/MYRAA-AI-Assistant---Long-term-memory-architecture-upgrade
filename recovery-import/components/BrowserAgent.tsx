import React, { useState } from "react";

export function BrowserAgent() {
  const [url, setUrl] = useState("");

  return (
    <section style={{ padding: 16, borderRadius: 12, background: "#172033", color: "white" }}>
      <h3>MYRAA Browser Agent</h3>
      <input
        value={url}
        onChange={(event) => setUrl(event.target.value)}
        placeholder="Enter a website URL"
        style={{ width: "100%", padding: 10, borderRadius: 8 }}
      />
      <button
        onClick={() => url.trim() && window.open(url, "_blank", "noopener,noreferrer")}
        style={{ marginTop: 10, padding: 10, borderRadius: 8 }}
      >
        Open Website
      </button>
    </section>
  );
}
