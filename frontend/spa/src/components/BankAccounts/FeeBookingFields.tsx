import { TransactionArea } from '@hopps/api-client';
import { useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import CategoryGroupFields from '@/components/CategoryGroups/CategoryGroupFields';
import { buildBommelIndex, missingRequiredGroups } from '@/components/CategoryGroups/helpers';
import { ALL_BOMMELS, BommelSelect } from '@/components/Dashboard/BommelSelect';
import { flattenBommelTree } from '@/components/Dashboard/bommelTree';
import { Label } from '@/components/ui/Label';
import Select from '@/components/ui/Select';
import Switch from '@/components/ui/Switch';
import { useCategoryGroups } from '@/hooks/queries/useCategoryGroups';
import { useBommelsStore } from '@/store/bommels/bommelsStore';
import { useStore } from '@/store/store';

const AREAS: TransactionArea[] = ['IDEELL', 'ZWECKBETRIEB', 'VERMOEGENSVERWALTUNG', 'WIRTSCHAFTLICH'];
/** The Select ignores an empty value, so "no area" needs a value of its own. */
const NO_AREA = 'NONE';

export interface FeeBookingState {
    enabled: boolean;
    bommelId: number | null;
    area: TransactionArea | null;
    categoryValues: Record<number, string>;
}

interface Props {
    value: FeeBookingState;
    onChange: (value: FeeBookingState) => void;
    /** Shown under the bommel picker, e.g. when fees are to be booked but no bommel is chosen. */
    bommelError?: string;
}

/**
 * Settings of a PayPal account for booking its fees on its own: every imported PayPal fee becomes a confirmed
 * transaction on the chosen bommel, with the chosen area and category values.
 */
export function FeeBookingFields({ value, onChange, bommelError }: Props) {
    const { t } = useTranslation();
    const { organization } = useStore();
    const allBommels = useBommelsStore((s) => s.allBommels);
    const loadBommels = useBommelsStore((s) => s.loadBommels);
    const { data: categoryGroups = [] } = useCategoryGroups();
    const bommelItems = useMemo(() => flattenBommelTree(allBommels), [allBommels]);

    // The bank account views don't load the bommel store themselves.
    useEffect(() => {
        if (value.enabled && organization?.id && allBommels.length === 0) {
            loadBommels(organization.id);
        }
    }, [value.enabled, organization?.id, allBommels.length, loadBommels]);

    const missing = value.bommelId != null ? missingRequiredGroups(categoryGroups, value.bommelId, buildBommelIndex(allBommels), value.categoryValues) : [];

    return (
        <div className="grid gap-3 rounded-xl border border-border-soft p-3.5">
            <div className="grid gap-1">
                <Switch
                    checked={value.enabled}
                    onCheckedChange={(enabled) => onChange({ ...value, enabled })}
                    label={t('bankAccounts.form.feeBooking.enabled')}
                />
                <p className="text-xs text-muted-foreground">{t('bankAccounts.form.feeBooking.hint')}</p>
            </div>

            {value.enabled && (
                <>
                    <div className="grid gap-1.5">
                        <Label>{t('bankAccounts.form.feeBooking.bommel')}</Label>
                        <BommelSelect
                            items={bommelItems}
                            value={value.bommelId ?? ALL_BOMMELS}
                            emptyLabel={t('invoiceUpload.selectBommel')}
                            // Values of groups that do not apply to the new bommel would only be dropped by the backend.
                            onChange={(next) => onChange({ ...value, bommelId: next === ALL_BOMMELS ? null : next, categoryValues: {} })}
                            triggerClassName="sm:w-full rounded-xl border-border-soft shadow-none hover:shadow-none"
                            matchTriggerWidth
                        />
                        {bommelError && <p className="text-xs text-destructive">{bommelError}</p>}
                    </div>

                    <Select
                        label={t('bankAccounts.form.feeBooking.area')}
                        value={value.area ?? NO_AREA}
                        items={[
                            { value: NO_AREA, label: t('bankAccounts.form.feeBooking.noArea') },
                            ...AREAS.map((area) => ({ value: area, label: t(`receipts.areas.${area.toLowerCase()}`) })),
                        ]}
                        onValueChanged={(area) => onChange({ ...value, area: area === NO_AREA ? null : (area as TransactionArea) })}
                    />

                    <CategoryGroupFields
                        bommelId={value.bommelId}
                        values={value.categoryValues}
                        onChange={(groupId, groupValue) => {
                            const categoryValues = { ...value.categoryValues };
                            if (groupValue == null || groupValue === '') delete categoryValues[groupId];
                            else categoryValues[groupId] = groupValue;
                            onChange({ ...value, categoryValues });
                        }}
                    />

                    {missing.length > 0 && (
                        <p className="text-xs text-[var(--warning)]">
                            {t('bankAccounts.form.feeBooking.staysDraft', { groups: missing.map((g) => g.name).join(', ') })}
                        </p>
                    )}
                </>
            )}
        </div>
    );
}
