import React, { useState, useEffect, useRef, useCallback } from "react";
import { Cpu } from "lucide-react";
import { API } from "./constants/api";
import { apiFetch } from "./utils/apiClient";
import Sidebar from "./components/Sidebar";
import StatusBar from "./components/StatusBar";
import DashboardView from "./components/DashboardView";
import DeviceManagerView from "./components/DeviceManagerView";
import TagBrowserView from "./components/TagBrowserView";
import DiagnosticsView from "./components/DiagnosticsView";
import SimulationView from "./components/SimulationView";
import ErrorBoundary from "./components/ErrorBoundary";
import "./App.scss";

const IS_DEDICATED_MODE = window.location.pathname.includes("standalone");
const MODULE_VERSION = "9.2.5";

type ConnectionStatus = "connecting" | "connected" | "disconnected" | "auth_required";

const App: React.FC = () => {
  const [status, setStatus] = useState<ConnectionStatus>("connecting");
  const [activeView, setActiveView] = useState("dashboard");
  const healthTimerRef = useRef<number | null>(null);
  const attemptsRef = useRef(0);
  const intervalRef = useRef(5000);

  const checkHealth = useCallback(async () => {
    try {
      const res = await apiFetch(API.AUTH_CHECK);

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
      <div className="app-wrapper">
        <div className="app-container">
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
      </div>
    );
  }

  const renderActiveView = () => {
    switch (activeView) {
      case 'dashboard':
        return <DashboardView onNavigate={setActiveView} />;
      case 'devices':
        return <DeviceManagerView />;
      case 'tags':
        return <TagBrowserView />;
      case 'diagnostics':
        return <DiagnosticsView />;
      case 'simulation':
        return <SimulationView />;
      default:
        return <DashboardView onNavigate={setActiveView} />;
    }
  };

  return (
    <ErrorBoundary>
      <div className="app-wrapper">
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
        <div className="app-outer-layout">
          <Sidebar
            activeView={activeView}
            onNavigate={setActiveView}
            moduleVersion={MODULE_VERSION}
          />
          <div className="app-content-area">
            <div className="app-active-view">
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
