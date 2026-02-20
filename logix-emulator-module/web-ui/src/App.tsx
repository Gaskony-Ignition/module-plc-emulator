import React, { useState, useEffect, useRef, useCallback } from "react";
import { Cpu, ExternalLink } from "lucide-react";
import "./App.scss";

const HEALTH_URL = "/data/logixemulator/health";
const STANDALONE_URL = "/res/logixemulator/standalone.html";
const IS_DEDICATED_MODE = window.location.pathname.includes("standalone");

type ConnectionStatus = "connecting" | "connected" | "disconnected" | "auth_required";

const App: React.FC = () => {
  const [status, setStatus] = useState<ConnectionStatus>("connecting");
  const healthTimerRef = useRef<number | null>(null);
  const attemptsRef = useRef(0);
  const intervalRef = useRef(5000);

  const checkHealth = useCallback(async () => {
    try {
      const res = await fetch(HEALTH_URL, {
        signal: AbortSignal.timeout(5000),
        credentials: "same-origin",
      });

      const contentType = res.headers.get("content-type") || "";
      if (contentType.includes("text/html") || res.status === 401 || res.status === 403) {
        setStatus("auth_required");
        intervalRef.current = 10000;
      } else if (res.ok) {
        setStatus("connected");
        attemptsRef.current = 0;
        intervalRef.current = 15000;
      } else {
        throw new Error(`HTTP ${res.status}`);
      }
    } catch {
      attemptsRef.current++;
      setStatus(attemptsRef.current > 2 ? "disconnected" : "connecting");
      intervalRef.current = Math.min(5000 * Math.pow(2, attemptsRef.current), 30000);
    }

    if (healthTimerRef.current) {
      window.clearTimeout(healthTimerRef.current);
    }
    healthTimerRef.current = window.setTimeout(checkHealth, intervalRef.current);
  }, []);

  useEffect(() => {
    checkHealth();
    return () => {
      if (healthTimerRef.current) {
        window.clearTimeout(healthTimerRef.current);
      }
    };
  }, [checkHealth]);

  if (status === "auth_required") {
    return (
      <div className="logix-app">
        <div className="auth-required-overlay">
          <div className="auth-required-card">
            <div className="auth-module-identity">
              <Cpu size={32} />
              <span>Logix PLC Emulator</span>
            </div>
            <div className="auth-divider" />
            <div className="auth-icon">&#128274;</div>
            <h2>Authentication Required</h2>
            <p>You must be logged in to the Ignition Gateway to use this module.</p>
            <a href="/web/login" target="_top" className="auth-login-btn">
              Log In to Gateway
            </a>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="logix-app">
      {status === "disconnected" && (
        <div className="connection-banner">
          Unable to connect to Logix PLC Emulator gateway. Retrying...
        </div>
      )}
      {IS_DEDICATED_MODE && (
        <div className="dedicated-header">
          <Cpu size={16} />
          <span>Logix PLC Emulator</span>
        </div>
      )}
      {!IS_DEDICATED_MODE && (
        <div className="logix-toolbar">
          <div className="logix-toolbar-brand">
            <Cpu size={18} />
            <span>Logix PLC Emulator</span>
          </div>
          <a
            className="logix-toolbar-popout"
            href={STANDALONE_URL}
            target="_blank"
            rel="noopener noreferrer"
            title="Open in dedicated page"
          >
            <ExternalLink size={14} />
            <span>Dedicated Page</span>
          </a>
        </div>
      )}
      <div className="logix-content">
        <iframe
          key="logixemulator-connection-browser"
          src="/data/logixemulator/connection-browser"
          className="logix-iframe"
          title="Logix PLC Emulator Connection Browser"
        />
      </div>
    </div>
  );
};

export default App;
