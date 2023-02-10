/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { Branch, DefaultApi } from "@app/services/nessie/client";
import { store } from "@app/store/store";
import { NessieRootState, Reference } from "@app/types/nessie";

export const INIT_REFS = "NESSIE_INIT_REFS";
type InitRefsAction = {
  type: typeof INIT_REFS;
};
export function initRefs() {
  return (dispatch: any) => dispatch({ type: INIT_REFS });
}
export type DatasetReference = {
  [key: string]: { type: string; value: string };
};
export const SET_REFS = "NESSIE_SET_REFS";
type SetRefsAction = {
  type: typeof SET_REFS;
  payload: DatasetReference;
};

export const REMOVE_ENTRY = "NESSIE_REMOVE_ENTRY";
type RemoveEntryAction = {
  type: typeof REMOVE_ENTRY;
  payload: string;
};
export function removeEntry(payload: RemoveEntryAction["payload"]) {
  return (dispatch: any) => dispatch({ type: REMOVE_ENTRY, payload });
}
export function setRefs(payload: NessieRootState) {
  return (dispatch: any) => dispatch({ type: SET_REFS, payload });
}
export type NessieRootActionTypes =
  | InitRefsAction
  | SetRefsAction
  | RemoveEntryAction
  | ResetNessieStateAction;

type SourceType = { source: string; meta?: any };

export const SET_REF = "NESSIE_SET_REF";
export const SET_REF_REQUEST = "NESSIE_SET_REF_REQUEST";
export const SET_REF_REQUEST_FAILURE = "NESSIE_SET_REF_REQUEST_FAILURE";
export type SetReferenceAction = SourceType & {
  type: typeof SET_REF;
  payload: {
    reference: Reference | null;
    hash?: string | null;
    date?: Date | null;
  };
};
type SetReferenceFailureAction = {
  type: typeof SET_REF_REQUEST_FAILURE;
} & SourceType;
export function setReference(
  payload: SetReferenceAction["payload"],
  source: string
): NessieActionTypes {
  return {
    type: SET_REF,
    payload,
    source,
    // Triggers reload of home contents when reference changes
    meta: { invalidateViewIds: ["HomeContents"] }, //TODO Importing this const was breaking imports from this file
  };
}
export const DEFAULT_REF_REQUEST = "NESSIE_DEFAULT_REF_REQUEST";
export const DEFAULT_REF_REQUEST_SUCCESS = "NESSIE_DEFAULT_REF_REQUEST_SUCCESS";
export const DEFAULT_REF_REQUEST_FAILURE = "NESSIE_DEFAULT_REF_REQUEST_FAILURE";
type FetchDefaultBranchAction = {
  type: typeof DEFAULT_REF_REQUEST;
} & SourceType;
type FetchDefaultBranchFailureAction = {
  type: typeof DEFAULT_REF_REQUEST_FAILURE;
} & SourceType;
type FetchDefaultBranchSuccessAction = {
  type: typeof DEFAULT_REF_REQUEST_SUCCESS;
  payload: Reference | null;
} & SourceType;

export const COMMIT_BEFORE_TIME_REQUEST = "NESSIE_COMMIT_BEFORE_TIME_REQUEST";
export const COMMIT_BEFORE_TIME_REQUEST_SUCCESS =
  "NESSIE_COMMIT_BEFORE_TIME_REQUEST_SUCCESS";
export const COMMIT_BEFORE_TIME_REQUEST_FAILURE =
  "NESSIE_COMMIT_BEFORE_TIME_REQUEST_FAILURE";
type FetchCommitBeforeTimeAction = {
  type: typeof COMMIT_BEFORE_TIME_REQUEST;
  payload: number;
} & SourceType;
type FetchCommitBeforeTimeSuccessAction = {
  type: typeof COMMIT_BEFORE_TIME_REQUEST_SUCCESS;
} & SourceType;
type FetchCommitBeforeTimeFailureAction = {
  type: typeof COMMIT_BEFORE_TIME_REQUEST_FAILURE;
} & SourceType;

export const NESSIE_RESET_STATE = "NESSIE_RESET_STATE";
type ResetNessieStateAction = {
  type: typeof NESSIE_RESET_STATE;
} & SourceType;

export type NessieActionTypes =
  | SetReferenceAction
  | SetReferenceFailureAction
  | FetchDefaultBranchAction
  | FetchDefaultBranchSuccessAction
  | FetchDefaultBranchFailureAction
  | FetchCommitBeforeTimeAction
  | FetchCommitBeforeTimeSuccessAction
  | FetchCommitBeforeTimeFailureAction
  | ResetNessieStateAction;

export function resetNessieState() {
  return (dispatch: any) => {
    return dispatch({
      type: NESSIE_RESET_STATE,
    });
  };
}

export function fetchDefaultReferenceIfNeeded(source: string, api: DefaultApi) {
  return async (dispatch: any) => {
    const nessie = store.getState().nessie as NessieRootState;
    if (nessie[source]?.defaultReference) return;
    return dispatch(fetchDefaultReference(source, api));
  };
}

export function fetchDefaultReference(source: string, api: DefaultApi) {
  return async (dispatch: any) => {
    if (!source) return;
    dispatch({ type: DEFAULT_REF_REQUEST, source });
    try {
      const reference = await api.getDefaultBranch();
      dispatch({
        type: DEFAULT_REF_REQUEST_SUCCESS,
        payload: reference,
        source,
      });
    } catch (e: any) {
      dispatch({
        type: DEFAULT_REF_REQUEST_FAILURE,
        source,
        payload: "There was an error fetching the arctic entity.",
      });
    }
  };
}

export function fetchBranchReference(
  source: string,
  api: DefaultApi,
  initialRef?: Branch
) {
  return async (dispatch: any) => {
    dispatch({ type: SET_REF_REQUEST, source });
    try {
      let curReference;
      if (initialRef?.name) {
        curReference = (await api.getReferenceByName({
          ref: initialRef?.name,
        })) as Reference;

        dispatch({
          type: SET_REF,
          payload: {
            reference: {
              ...curReference,
              hash: initialRef?.hash ?? null,
            },
            hash: initialRef?.hash ?? null,
          },
          source,
        });
      }
    } catch (e) {
      dispatch({
        type: SET_REF_REQUEST_FAILURE,
        payload: `There was an error fetching the reference: ${initialRef?.name}.`,
        source,
        meta: {
          notification: {
            message: la(
              `There was an error fetching the reference: ${initialRef?.name}.`
            ),
            level: "error",
          },
        },
      });
    }
  };
}

export function fetchCommitBeforeTime(
  reference: Reference | null,
  date: Date,
  source: string,
  api: DefaultApi
) {
  return async (dispatch: any) => {
    let result = null;
    if (!reference) return result;
    dispatch({ type: COMMIT_BEFORE_TIME_REQUEST, source });
    try {
      const timestampISO = date.toISOString();
      const log = await api.getCommitLog({
        ref: reference.name,
        maxRecords: 1,
        filter: `timestamp(commit.commitTime) <= timestamp('${timestampISO}')`,
      });
      const hash = log?.logEntries?.[0]?.commitMeta?.hash || "";
      if (hash) {
        dispatch({ type: COMMIT_BEFORE_TIME_REQUEST_SUCCESS, source });
        result = { reference, hash, date };
      } else {
        dispatch({
          type: COMMIT_BEFORE_TIME_REQUEST_FAILURE,
          source,
          meta: {
            notification: {
              message: la("No commit found for provided date."),
              level: "warning",
              autoDismiss: 3,
            },
          },
        });
      }
    } catch (e) {
      dispatch({
        type: COMMIT_BEFORE_TIME_REQUEST_FAILURE,
        source,
        meta: {
          notification: {
            message: la("There was an error fetching the commit."),
            level: "error",
          },
        },
      });
    }

    return result;
  };
}
