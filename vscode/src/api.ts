/**
 * Client for the SQLens JSON API:
 *
 * - GET  /api/datasources  the configured datasources
 * - POST /api/query        runs a statement, returns columns and rows
 * - POST /api/export       runs a statement, streams the result as a file
 */
import { createWriteStream } from 'node:fs';
import { rm } from 'node:fs/promises';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import type { ReadableStream as WebReadableStream } from 'node:stream/web';

export interface Datasource {
  name: string;
  description: string | null;
  url: string;
  user: string | null;
  readOnly: boolean;
}

export interface DatasourceList {
  datasources: Datasource[];
  configFile: string;
  driversDir: string;
  drivers: string[];
  driverJarErrors: unknown;
  maxGridRows: number;
  /** Why datasources.json could not be read, or null. */
  error: string | null;
}

export interface Column {
  name: string;
  jdbcType: string;
  dbType: string | null;
  numeric: boolean;
}

export type Cell = string | number | boolean | null;

export interface QueryResult {
  datasource: string;
  columns: Column[];
  rows: Cell[][];
  rowCount: number;
  truncated: boolean;
  /** The server's maxGridRows, when it lowered the row limit asked for. */
  cappedAt: number | null;
  /** Rows affected, for a statement that returns no rows. */
  updateCount: number | null;
  elapsedMs: number;
  /** Set when the query failed after some rows were already sent. */
  error: string | null;
}

export interface QueryRequest {
  datasource: string;
  sql: string;
  /** 0 for no limit. */
  maxRows: number;
}

export interface ExportRequest extends QueryRequest {
  format?: string;
  delimiter?: string;
  decimalSeparator?: string;
}

/** An error reply from the API, with the SQL error details when there are any. */
export class ApiError extends Error {
  constructor(message: string, readonly status: number, readonly sqlState?: string, readonly errorCode?: number) {
    super(message);
  }
}

export class SqlensClient {
  constructor(private readonly baseUrl: string) {}

  async datasources(signal?: AbortSignal): Promise<DatasourceList> {
    const response = await this.request('GET', 'datasources', undefined, signal);
    return (await response.json()) as DatasourceList;
  }

  async query(request: QueryRequest, signal?: AbortSignal): Promise<QueryResult> {
    const response = await this.request('POST', 'query', request, signal);
    return (await response.json()) as QueryResult;
  }

  /**
   * Streams an export into a file. The server cuts the connection off when the
   * query fails after rows were sent, so any stream error removes the partial
   * file rather than leaving a CSV that looks complete.
   */
  async exportToFile(request: ExportRequest, file: string, signal?: AbortSignal): Promise<void> {
    const response = await this.request('POST', 'export', request, signal);
    if (!response.body) {
      throw new Error('The server sent an empty response');
    }
    try {
      await pipeline(Readable.fromWeb(response.body as WebReadableStream), createWriteStream(file), { signal });
    } catch (e) {
      await rm(file, { force: true });
      if (signal?.aborted) {
        throw e;
      }
      throw new Error(`Export failed after rows were written, so the file was removed: ${describe(e)}`);
    }
  }

  private async request(method: string, path: string, body: unknown, signal?: AbortSignal): Promise<Response> {
    const url = `${this.baseUrl.replace(/\/+$/, '')}/api/${path}`;
    let response: Response;
    try {
      response = await fetch(url, {
        method,
        headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal,
      });
    } catch (e) {
      if (signal?.aborted) {
        throw e;
      }
      throw new Error(`Cannot reach SQLens at ${url}: ${describe(e)}`);
    }
    if (!response.ok) {
      throw await apiError(response);
    }
    return response;
  }
}

async function apiError(response: Response): Promise<ApiError> {
  const text = await response.text();
  try {
    const body = JSON.parse(text);
    if (body && typeof body.error === 'string') {
      return new ApiError(body.error, response.status, body.sqlState ?? undefined, body.errorCode ?? undefined);
    }
  } catch {
    // Not JSON, e.g. a Tomcat error page.
  }
  return new ApiError(`HTTP ${response.status} ${response.statusText}`, response.status);
}

export function describe(e: unknown): string {
  if (e instanceof Error) {
    const cause = (e as { cause?: unknown }).cause;
    return cause instanceof Error ? `${e.message} (${cause.message})` : e.message;
  }
  return String(e);
}
