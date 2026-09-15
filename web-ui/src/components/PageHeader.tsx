import type { LucideIcon } from 'lucide-react'
import './PageHeader.css'

interface PageHeaderProps {
  icon: LucideIcon
  title: string
  subtitle: string
  badge?: string
  children?: React.ReactNode
}

export default function PageHeader({ icon: Icon, title, subtitle, badge, children }: PageHeaderProps) {
  return (
    <div className="page-header">
      <div className="page-header-left">
        <Icon size={20} className="page-header-icon" />
        <div>
          <h2>{title}{badge && <span className="page-header-badge">{badge}</span>}</h2>
          <p>{subtitle}</p>
        </div>
      </div>
      {children && <div className="page-header-right">{children}</div>}
    </div>
  )
}
