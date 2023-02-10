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

import { useIntl } from "react-intl";
import CopyButton from "../../../../components/Buttons/CopyButton";
import * as classes from "./LabelWithCopyButton.module.less";

export const LabelWithCopyButton = ({
  label,
  copyText,
  copyTitle,
}: {
  label: string;
  copyText: string;
  copyTitle: string;
}) => {
  const { formatMessage } = useIntl();
  return (
    <div className={classes["label-with-copy"]}>
      {formatMessage({ id: label })}
      <CopyButton
        style={{ marginLeft: "auto" }}
        buttonStyle={{ paddingBottom: "inherit" }}
        title={copyTitle}
        text={copyText}
      />
    </div>
  );
};
