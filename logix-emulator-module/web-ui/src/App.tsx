import React, { useState, useEffect, useRef, useCallback } from "react";
import { Cpu } from "lucide-react";
import Sidebar from "./components/Sidebar";
import StatusBar from "./components/StatusBar";
import DashboardView from "./components/DashboardView";
import DevicesView from "./components/DevicesView";
import TagsView from "./components/TagsView";
import DiagnosticsView from "./components/DiagnosticsView";
import LogsView from "./components/LogsView";
import SimulationView from "./components/SimulationView";
import ErrorBoundary from "./components/ErrorBoundary";
import "./App.scss";

const AUTH_CHECK_URL = "/data/logixemulator/auth/check";
const IS_DEDICATED_MODE = window.location.pathname.includes("standalone");
const MODULE_VERSION = "9.0.1";

type ConnectionStatus = "connecting" | "connected" | "disconnected" | "auth_required";

const App: React.FC = () => {
  const [status, setStatus] = useState<ConnectionStatus>("connecting");
  const [activeView, setActiveView] = useState("dashboard");
  const healthTimerRef = useRef<number | null>(null);
  const attemptsRef = useRef(0);
  const intervalRef = useRef(5000);

  const checkHealth = useCallback(async () => {
    try {
      const res = await fetch(AUTH_CHECK_URL, {
        signal: AbortSignal.timeout(5000),
        credentials: "same-origin",
      });

      if (res.status === 401) {
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

  const renderActiveView = () => {
    switch (activeView) {
      case 'dashboard':
        return <DashboardView onNavigate={setActiveView} />;
      case 'devices':
        return <DevicesView />;
      case 'tags':
        return <TagsView />;
      case 'diagnostics':
        return <DiagnosticsView />;
      case 'logs':
        return <LogsView />;
      case 'simulation':
        return <SimulationView />;
      default:
        return <DashboardView onNavigate={setActiveView} />;
    }
  };

  return (
    <ErrorBoundary>
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
        <div className="logix-outer-layout">
          <Sidebar
            activeView={activeView}
            onNavigate={setActiveView}
            moduleVersion={MODULE_VERSION}
          />
          <div className="logix-content-area">
            <div className="logix-active-view">
              {renderActiveView()}
            </div>
            <StatusBar />
          </div>
        </div>
      </div>
    </ErrorBoundary>
  );
};

export default App;
