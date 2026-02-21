import React, { Component, ErrorInfo, ReactNode } from 'react'
import { AlertCircle, RefreshCw } from 'lucide-react'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
}

class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary] Uncaught error:', error, info.componentStack)
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null })
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          height: '100vh',
          background: '#1e1e2e',
          color: '#cdd6f4',
          fontFamily: 'sans-serif',
          gap: '16px',
          padding: '24px',
          textAlign: 'center',
        }}>
          <AlertCircle size={48} color="#f38ba8" />
          <h2 style={{ margin: 0, fontSize: '20px', color: '#f38ba8' }}>
            Something went wrong
          </h2>
          <p style={{ margin: 0, color: '#a6adc8', maxWidth: '480px', fontSize: '14px' }}>
            The Logix PLC Emulator UI encountered an unexpected error.
          </p>
          {this.state.error && (
            <pre style={{
              background: '#181825',
              padding: '12px 16px',
              borderRadius: '8px',
              fontSize: '12px',
              color: '#f38ba8',
              maxWidth: '600px',
              overflow: 'auto',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
            }}>
              {this.state.error.message}
            </pre>
          )}
          <button
            onClick={this.handleReset}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              padding: '8px 16px',
              background: '#313244',
              color: '#cdd6f4',
              border: '1px solid #45475a',
              borderRadius: '6px',
              cursor: 'pointer',
              fontSize: '13px',
            }}
          >
            <RefreshCw size={14} />
            Retry
          </button>
        </div>
      )
    }

    return this.props.children
  }
}

export default ErrorBoundary
