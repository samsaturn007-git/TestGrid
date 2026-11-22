import type { ReactNode } from 'react';

/** Page heading plus the document title (React 19 hoists <title> into <head>). */
export function Page({
  title,
  description,
  children,
}: {
  title: string;
  description?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <>
      <title>{`${title} · TestGrid`}</title>
      <div className="flex flex-col gap-1">
        <h1 className="text-title">{title}</h1>
        {description ? <p className="text-body text-text-muted">{description}</p> : null}
      </div>
      {children}
    </>
  );
}
