/**
 * The latest query and its result, for features that act on the grid such as
 * export and copy. Listeners get {request, response} after every finished
 * query; request is the body sent to POST api/query ({datasource, sql, maxRows}),
 * response its parsed JSON. A failed query gives a response with only `error`.
 */
const listeners = new Set();

export const latest = { request: null, response: null };

export function onResult(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function publishResult(request, response) {
  latest.request = request;
  latest.response = response;
  for (const listener of listeners) listener(latest);
}
