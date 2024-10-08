/**
 * This file was auto-generated by openapi-typescript.
 * Do not make direct changes to the file.
 */


export interface paths {
  "/user": {
    get: {
      responses: {
        /** @description null */
        200: {
          content: {
            "application/json": {
              data: components["schemas"]["User"][];
              itemsPerPage: number;
              startIndex: number;
              totalResults: number;
            };
          };
        };
      };
    };
  };
}

export type webhooks = Record<string, never>;

export interface components {
  schemas: {
    User: {
      active: boolean;
      /** Format: email */
      email: string;
      firstName: string;
      /** Format: uuid */
      id: string;
      lastName: string;
      name: string;
      tag: string;
    };
  };
  responses: never;
  parameters: never;
  requestBodies: never;
  headers: never;
  pathItems: never;
}

export type $defs = Record<string, never>;

export type external = Record<string, never>;

export type operations = Record<string, never>;
