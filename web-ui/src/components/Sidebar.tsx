import React, { useState, useEffect, useCallback } from 'react'
import {
  LayoutDashboard, HardDrive, Tag, Activity, Zap,
  ChevronLeft, ChevronRight, ExternalLink
} from 'lucide-react'
import './Sidebar.css'

interface SidebarProps {
  activeView: string
  onNavigate: (view: string) => void
  moduleVersion?: string
}

interface NavItem {
  id: string
  label: string
  icon: React.ComponentType<{ size?: number | string }>
  enabled: boolean
  beta?: boolean
}

const navItems: NavItem[] = [
  { id: 'dashboard', label: 'Dashboard', icon: LayoutDashboard, enabled: true },
  { id: 'devices', label: 'Devices', icon: HardDrive, enabled: true },
  { id: 'tags', label: 'Tags', icon: Tag, enabled: true },
  { id: 'simulation', label: 'Simulation', icon: Zap, enabled: true, beta: true },
  { id: 'diagnostics', label: 'Diagnostics', icon: Activity, enabled: true },
]

const STORAGE_KEY = 'logix-sidebar-collapsed'

function Sidebar({ activeView, onNavigate, moduleVersion }: SidebarProps) {
  const [isCollapsed, setIsCollapsed] = useState<boolean>(() => {
    return localStorage.getItem(STORAGE_KEY) === 'true'
  })

  const toggleCollapse = useCallback(() => {
    setIsCollapsed(prev => {
      const next = !prev
      localStorage.setItem(STORAGE_KEY, next.toString())
      return next
    })
  }, [])

  // Keyboard shortcut: Ctrl+B toggles collapse
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'b') {
        e.preventDefault()
        toggleCollapse()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [toggleCollapse])

  const handleItemClick = useCallback((item: NavItem) => {
    if (!item.enabled) return
    onNavigate(item.id)
  }, [onNavigate])

  return (
    <nav className={`nav-sidebar ${isCollapsed ? 'collapsed' : 'expanded'}`}>
      <div className="nav-sidebar-items">
        {navItems.map(item => {
          const Icon = item.icon
          const isActive = activeView === item.id
          const classNames = [
            'nav-sidebar-item',
            isActive ? 'active' : '',
            !item.enabled ? 'disabled' : '',
          ].filter(Boolean).join(' ')

          return (
            <button
              key={item.id}
              className={classNames}
              onClick={() => handleItemClick(item)}
              title={!isCollapsed && item.enabled ? item.label : undefined}
              aria-label={item.label}
              aria-current={isActive ? 'page' : undefined}
              disabled={!item.enabled}
            >
              <span className="nav-sidebar-item-icon">
                <Icon size={18} />
              </span>
              <span className="nav-sidebar-item-label">
                {item.label}
                {item.beta && <span className="nav-sidebar-beta-badge">Beta</span>}
              </span>
              {(isCollapsed || !item.enabled) && (
                <span className="nav-sidebar-tooltip">
                  {item.enabled ? `${item.label}${item.beta ? ' (Beta)' : ''}` : `${item.label} - Coming Soon`}
                </span>
              )}
            </button>
          )
        })}
      </div>

      {moduleVersion && (
        <div className="nav-sidebar-version" title={`Module v${moduleVersion}`}>
          <span className="nav-sidebar-version-text">v{moduleVersion}</span>
        </div>
      )}

      <button
        className="nav-sidebar-dedicated-btn"
        onClick={() => window.open('/res/logixemulator/standalone.html', '_blank')}
        title="Open dedicated full-page view"
        aria-label="Open dedicated full-page view"
      >
        <ExternalLink size={14} />
        <span className="nav-sidebar-dedicated-label">Dedicated Page</span>
      </button>

      <button
        className="nav-sidebar-toggle"
        onClick={toggleCollapse}
        title={isCollapsed ? 'Expand sidebar (Ctrl+B)' : 'Collapse sidebar (Ctrl+B)'}
        aria-label={isCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
      >
        {isCollapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
        <span className="nav-sidebar-toggle-label">Collapse</span>
      </button>
    </nav>
  )
}

export default Sidebar
