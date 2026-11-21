import { useEffect, useRef } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import { useCurrentProject } from '../api/queries.ts';
import { NewRunButton } from '../runs/NewRunButton.tsx';
import { NewRunProvider } from '../runs/NewRunProvider.tsx';
import { ThemeToggle } from './ThemeToggle.tsx';

const NAV_ICONS = {
  overview: 'M2.5 2.5h4.5v4.5H2.5zM9 2.5h4.5v4.5H9zM2.5 9h4.5v4.5H2.5zM9 9h4.5v4.5H9z',
  runs: 'M3 4h10M3 8h10M3 12h6',
  suites: 'M8 2.5l5.5 3L8 8.5 2.5 5.5zM2.5 8.5L8 11.5l5.5-3M2.5 11L8 14l5.5-3',
  workers: 'M3 3h10v4H3zM3 9h10v4H3zM5.5 5h.01M5.5 11h.01',
};

function NavItem({ to, icon, label }: { to: string; icon: string; label: string }) {
  return (
    <NavLink
      to={to}
      end={to === '/'}
      className={({ isActive }) =>
        [
          'flex h-[34px] items-center gap-2.5 rounded-chip px-2.5 text-body no-underline',
          isActive
            ? 'bg-raised font-medium text-text'
            : 'text-text-secondary hover:bg-raised hover:text-text',
        ].join(' ')
      }
    >
      <svg
        width="16"
        height="16"
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d={icon} />
      </svg>
      {label}
    </NavLink>
  );
}

function Logo() {
  return (
    <svg width="22" height="22" viewBox="0 0 22 22" fill="none" aria-hidden="true">
      <rect x="1" y="1" width="20" height="20" rx="5" className="stroke-text" strokeWidth="1.5" />
      <path
        d="M7.5 1v20M14.5 1v20M1 7.5h20M1 14.5h20"
        className="stroke-neutral-rim"
        strokeWidth="1"
      />
      <rect x="8.25" y="8.25" width="5.5" height="5.5" rx="1" className="fill-text" />
    </svg>
  );
}

export function AppShell() {
  const { project } = useCurrentProject();
  const location = useLocation();
  const mainRef = useRef<HTMLElement>(null);
  const firstRender = useRef(true);

  // A route change moves focus to the new page's content, so screen-reader and keyboard users
  // start at the top of what changed rather than at the link they just pressed.
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    mainRef.current?.focus();
  }, [location.pathname]);

  return (
    <NewRunProvider>
      <div className="flex min-h-screen bg-canvas text-text">
        <a
          href="#main"
          className="fixed left-3 top-3 z-50 -translate-y-20 rounded-chip bg-primary-bg px-3 py-2 font-semibold text-primary-fg no-underline focus:translate-y-0"
        >
          Skip to content
        </a>

        <nav
          aria-label="Primary"
          className="sticky top-0 flex h-screen w-[220px] shrink-0 flex-col gap-5 border-r border-hairline-soft px-3 py-4"
        >
          <div className="flex items-center gap-2.5 px-2 py-1">
            <Logo />
            <span className="text-[15px] font-bold tracking-[-0.01em]">TestGrid</span>
          </div>
          <div className="flex flex-col gap-0.5">
            <NavItem to="/" icon={NAV_ICONS.overview} label="Overview" />
            <NavItem to="/runs" icon={NAV_ICONS.runs} label="Runs" />
            <NavItem
              to={project ? `/projects/${project.id}/suites` : '/suites'}
              icon={NAV_ICONS.suites}
              label="Suites"
            />
            <NavItem to="/workers" icon={NAV_ICONS.workers} label="Workers" />
          </div>
        </nav>

        <div className="flex min-w-0 flex-1 flex-col">
          <header className="flex min-h-14 flex-wrap items-center gap-3 border-b border-hairline-soft px-6 py-2">
            {project ? (
              <span className="flex items-center gap-2 text-body">
                <span className="text-text-muted">Project</span>
                <span className="font-semibold">{project.name}</span>
              </span>
            ) : null}
            <div className="ml-auto flex items-center gap-3">
              <ThemeToggle />
              {project ? <NewRunButton /> : null}
            </div>
          </header>

          <main
            id="main"
            ref={mainRef}
            tabIndex={-1}
            className="flex w-full max-w-[1320px] flex-col gap-7 px-8 pb-10 pt-7 outline-none"
          >
            <Outlet />
          </main>
        </div>
      </div>
    </NewRunProvider>
  );
}
