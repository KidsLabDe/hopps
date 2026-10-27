import { zodResolver } from '@hookform/resolvers/zod';
import { ApiException, BankAccountResponse, BankAccountType, FeeBookingDto } from '@hopps/api-client';
import { useEffect, useState } from 'react';
import { Resolver, useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { z } from 'zod';

import { FeeBookingFields, type FeeBookingState } from '@/components/BankAccounts/FeeBookingFields';
import { LoadingState } from '@/components/common/LoadingState';
import Button from '@/components/ui/Button';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/Dialog';
import { Label } from '@/components/ui/Label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/RadioGroup';
import { BaseInput } from '@/components/ui/shadecn/BaseInput';
import { useCreateBankAccount, useUpdateBankAccount } from '@/hooks/queries/useBankAccounts';
import { useCurrency } from '@/hooks/use-currency';
import { currencyForIban, type CurrencyCode } from '@/lib/currency';
import { caretAfterChars, formatIban, normalizeIban } from '@/lib/iban';
import { cn } from '@/lib/utils';

const ACCT_COLORS = ['#9955CC', '#2E9E6B', '#2A6FDB', '#C8385A', '#B47C18', '#5B5BD6'];

const IBAN_FORMAT = /^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$/;

const ACCOUNT_TYPES: BankAccountType[] = ['BANK', 'PAYPAL'];

const NO_FEE_BOOKING: FeeBookingState = { enabled: false, bommelId: null, area: null, categoryValues: {} };

/**
 * @param organizationCurrency accounts are kept in it (no conversion yet), so the IBAN has to fit it: CH/LI means CHF,
 *            everything else EUR.
 * @param storedIban the IBAN the account is saved with. The rule only applies to a new or changed IBAN (like in the
 *            backend), so an account created before the rule can still be edited.
 *
 * A bank account is identified by its IBAN, a PayPal account by its PayPal e-mail address instead; only the field of
 * the chosen type is checked.
 */
function buildSchema(t: (k: string, o?: Record<string, unknown>) => string, organizationCurrency: CurrencyCode, storedIban?: string) {
    return z
        .object({
            name: z.string().min(1, t('bankAccounts.form.validation.nameRequired')),
            type: z.enum(ACCOUNT_TYPES),
            iban: z.string().transform(normalizeIban),
            paypalEmail: z.string().transform((v) => v.trim()),
            bankName: z.string().optional(),
            openingBalance: z.preprocess((v) => (v === '' || v === null || v === undefined ? undefined : Number(v)), z.number().optional()),
            openingBalanceDate: z.string().optional(),
            color: z.string().optional(),
        })
        .superRefine((data, ctx) => {
            const fail = (path: 'iban' | 'paypalEmail', key: string, options?: Record<string, unknown>) =>
                ctx.addIssue({ code: z.ZodIssueCode.custom, message: t(key, options), path: [path] });
            if (data.type === 'PAYPAL') {
                if (!data.paypalEmail) fail('paypalEmail', 'bankAccounts.form.validation.paypalEmailRequired');
                else if (!z.email().safeParse(data.paypalEmail).success) fail('paypalEmail', 'bankAccounts.form.validation.paypalEmailInvalid');
            } else if (!data.iban) {
                fail('iban', 'bankAccounts.form.validation.ibanRequired');
            } else if (data.iban.length < 15) {
                fail('iban', 'bankAccounts.form.validation.ibanTooShort');
            } else if (data.iban.length > 34) {
                fail('iban', 'bankAccounts.form.validation.ibanTooLong');
            } else if (!IBAN_FORMAT.test(data.iban)) {
                fail('iban', 'bankAccounts.form.validation.ibanInvalidFormat');
            } else {
                const ibanCurrency = currencyForIban(data.iban);
                const unchanged = storedIban !== undefined && normalizeIban(storedIban) === data.iban;
                if (ibanCurrency !== organizationCurrency && !unchanged) {
                    fail('iban', 'bankAccounts.form.validation.ibanOtherCurrency', {
                        country: data.iban.slice(0, 2),
                        ibanCurrency,
                        organizationCurrency,
                    });
                }
            }
            const hasBalance = data.openingBalance !== undefined;
            const hasDate = !!data.openingBalanceDate;
            if (hasBalance && !hasDate) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: t('bankAccounts.form.validation.openingBalanceDateRequired'),
                    path: ['openingBalanceDate'],
                });
            }
            if (hasDate && !hasBalance) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: t('bankAccounts.form.validation.openingBalanceRequired'),
                    path: ['openingBalance'],
                });
            }
        });
}

type FormValues = z.infer<ReturnType<typeof buildSchema>>;

/**
 * The fee booking to send: the settings when switched on, an empty one to switch a stored booking off (a missing
 * bommel means off), nothing when there is nothing to change.
 */
function feeBookingPayload(type: BankAccountType, feeBooking: FeeBookingState, stored: boolean): FeeBookingDto | undefined {
    if (type === 'PAYPAL' && feeBooking.enabled) {
        return new FeeBookingDto({
            bommelId: feeBooking.bommelId ?? undefined,
            area: feeBooking.area ?? undefined,
            categoryValues: feeBooking.categoryValues,
        });
    }
    return stored ? new FeeBookingDto() : undefined;
}

interface BankAccountDrawerProps {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    account?: BankAccountResponse;
    /** Called after saving, with the saved account (e.g. to select a newly created one). */
    onSuccess?: (account: BankAccountResponse) => void;
}

export function BankAccountDrawer({ open, onOpenChange, account, onSuccess }: BankAccountDrawerProps) {
    // Accounts are kept in the organization's currency; the backend sets it, the form only checks that the IBAN fits.
    const { currency: organizationCurrency } = useCurrency();
    const { t } = useTranslation();
    const schema = buildSchema(t, organizationCurrency, account?.iban);
    const isEdit = !!account;

    const createMutation = useCreateBankAccount();
    const updateMutation = useUpdateBankAccount();
    const isLoading = createMutation.isPending || updateMutation.isPending;

    const {
        register,
        handleSubmit,
        reset,
        setValue,
        watch,
        setError,
        formState: { errors },
    } = useForm<FormValues>({
        // zod's preprocess makes the schema's input type differ from FormValues (its output); cast the resolver to the
        // output type so useForm, handleSubmit and the field helpers all operate on the resolved FormValues.
        resolver: zodResolver(schema) as Resolver<FormValues>,
        defaultValues: {
            name: '',
            type: 'BANK',
            iban: '',
            paypalEmail: '',
            bankName: '',
            openingBalance: undefined,
            openingBalanceDate: '',
            color: ACCT_COLORS[0],
        },
    });

    // Kept outside the form: the category values are keyed by group and edited by their own pickers.
    const [feeBooking, setFeeBooking] = useState<FeeBookingState>(NO_FEE_BOOKING);
    const [feeBommelError, setFeeBommelError] = useState<string>();

    useEffect(() => {
        if (open) {
            const stored = account?.feeBooking;
            setFeeBooking(
                stored?.bommelId != null
                    ? { enabled: true, bommelId: stored.bommelId, area: stored.area ?? null, categoryValues: { ...(stored.categoryValues ?? {}) } }
                    : NO_FEE_BOOKING
            );
            setFeeBommelError(undefined);
            reset(
                account
                    ? {
                          name: account.name ?? '',
                          type: account.type ?? 'BANK',
                          iban: formatIban(account.iban ?? ''),
                          paypalEmail: account.paypalEmail ?? '',
                          bankName: account.bankName ?? '',
                          openingBalance: account.openingBalance ?? undefined,
                          openingBalanceDate: account.openingBalanceDate
                              ? (account.openingBalanceDate instanceof Date ? account.openingBalanceDate : new Date(account.openingBalanceDate))
                                    .toISOString()
                                    .slice(0, 10)
                              : '',
                          color: account.color ?? ACCT_COLORS[0],
                      }
                    : {
                          name: '',
                          type: 'BANK',
                          iban: '',
                          paypalEmail: '',
                          bankName: '',
                          openingBalance: undefined,
                          openingBalanceDate: '',
                          color: ACCT_COLORS[0],
                      }
            );
        }
    }, [open, account, reset]);

    const onSubmit = async (values: FormValues) => {
        const { type, iban, paypalEmail, ...rest } = values;
        const openingBalanceDate = rest.openingBalanceDate ? new Date(rest.openingBalanceDate) : undefined;
        // Only the identifier of the account's type is sent; the type itself is fixed once the account exists.
        const identifier = type === 'PAYPAL' ? { paypalEmail } : { iban };
        if (type === 'PAYPAL' && feeBooking.enabled && feeBooking.bommelId == null) {
            setFeeBommelError(t('bankAccounts.form.feeBooking.bommelRequired'));
            return;
        }
        const payload = {
            ...rest,
            ...identifier,
            bankName: type === 'PAYPAL' ? undefined : rest.bankName,
            openingBalanceDate,
            feeBooking: feeBookingPayload(type, feeBooking, !!account?.feeBooking),
        };
        try {
            const saved =
                isEdit && account?.id
                    ? await updateMutation.mutateAsync({ id: account.id, data: payload })
                    : await createMutation.mutateAsync({ ...payload, type });
            onSuccess?.(saved);
        } catch (err) {
            if (ApiException.isApiException(err) && err.status === 409) {
                if (type === 'PAYPAL') setError('paypalEmail', { message: t('bankAccounts.form.validation.duplicatePaypalEmail') });
                else setError('iban', { message: t('bankAccounts.form.validation.duplicateIban') });
            }
        }
    };

    const selectedColor = watch('color') ?? ACCT_COLORS[0];
    const isPaypal = watch('type') === 'PAYPAL';

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            {/* With the fee booking of a PayPal account the form can outgrow the window, so it scrolls. */}
            <DialogContent className="max-w-md max-h-[90vh] overflow-y-auto">
                <DialogHeader>
                    <DialogTitle>{isEdit ? t('bankAccounts.form.editTitle') : t('bankAccounts.form.createTitle')}</DialogTitle>
                    <p className="text-sm text-muted-foreground">{t('konten.form.subtitle')}</p>
                </DialogHeader>

                <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-4 pt-1">
                    {/* Name */}
                    <div className="grid gap-1.5">
                        <Label htmlFor="ba-name">{t('bankAccounts.form.name')}</Label>
                        <BaseInput id="ba-name" {...register('name')} placeholder={t('bankAccounts.form.namePlaceholder')} autoFocus />
                        {errors.name && <p className="text-xs text-destructive">{errors.name.message}</p>}
                    </div>

                    {/* Account type: chosen once, when the account is created */}
                    <div className="grid gap-1.5">
                        <Label>{t('bankAccounts.form.type')}</Label>
                        <RadioGroup
                            value={watch('type')}
                            onValueChange={(v) => setValue('type', v as BankAccountType)}
                            disabled={isEdit}
                            className="flex gap-5"
                            aria-label={t('bankAccounts.form.type')}
                        >
                            {ACCOUNT_TYPES.map((type) => (
                                <label key={type} className="flex items-center gap-2 text-sm cursor-pointer">
                                    <RadioGroupItem value={type} id={`ba-type-${type}`} />
                                    {t(`bankAccounts.form.types.${type}`)}
                                </label>
                            ))}
                        </RadioGroup>
                    </div>

                    {isPaypal ? (
                        /* PayPal e-mail address, the PayPal account's identifier */
                        <div className="grid gap-1.5">
                            <Label htmlFor="ba-paypalEmail">{t('bankAccounts.form.paypalEmail')}</Label>
                            <BaseInput
                                id="ba-paypalEmail"
                                type="email"
                                {...register('paypalEmail')}
                                placeholder={t('bankAccounts.form.paypalEmailPlaceholder')}
                            />
                            {errors.paypalEmail && <p className="text-xs text-destructive">{errors.paypalEmail.message}</p>}
                        </div>
                    ) : (
                        <>
                            {/* IBAN */}
                            <div className="grid gap-1.5">
                                <Label htmlFor="ba-iban">{t('bankAccounts.form.iban')}</Label>
                                <BaseInput
                                    id="ba-iban"
                                    {...register('iban', {
                                        // Group in fours while typing or pasting ("CH93 0076 2011 …"); the schema strips the
                                        // spaces again before saving. The caret stays behind the character just typed.
                                        onChange: (e: React.ChangeEvent<HTMLInputElement>) => {
                                            const input = e.target;
                                            const charsBeforeCaret = normalizeIban(input.value.slice(0, input.selectionStart ?? input.value.length)).length;
                                            const formatted = formatIban(input.value);
                                            if (formatted !== input.value) {
                                                setValue('iban', formatted);
                                                const caret = caretAfterChars(formatted, charsBeforeCaret);
                                                input.setSelectionRange(caret, caret);
                                            }
                                        },
                                    })}
                                    placeholder="DE00 0000 0000 0000 0000 00"
                                    className="font-mono"
                                />
                                {errors.iban && <p className="text-xs text-destructive">{errors.iban.message}</p>}
                            </div>

                            {/* Bank */}
                            <div className="grid gap-1.5">
                                <Label htmlFor="ba-bankName">{t('bankAccounts.form.bankName')}</Label>
                                <BaseInput id="ba-bankName" {...register('bankName')} placeholder={t('konten.form.bankPlaceholder')} />
                            </div>
                        </>
                    )}

                    {/* Opening Balance + Date side by side */}
                    <div className="grid grid-cols-2 gap-3">
                        <div className="grid gap-1.5">
                            <Label htmlFor="ba-openingBalance">{t('bankAccounts.form.openingBalance')}</Label>
                            <BaseInput
                                id="ba-openingBalance"
                                type="number"
                                step="0.01"
                                {...register('openingBalance')}
                                placeholder="0,00"
                                className="font-mono"
                            />
                            {errors.openingBalance && <p className="text-xs text-destructive">{errors.openingBalance.message}</p>}
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="ba-openingBalanceDate">{t('bankAccounts.form.openingBalanceDate')}</Label>
                            <BaseInput id="ba-openingBalanceDate" type="date" {...register('openingBalanceDate')} />
                            {errors.openingBalanceDate && <p className="text-xs text-destructive">{errors.openingBalanceDate.message}</p>}
                        </div>
                    </div>

                    {isPaypal && (
                        <FeeBookingFields
                            value={feeBooking}
                            onChange={(next) => {
                                setFeeBooking(next);
                                setFeeBommelError(undefined);
                            }}
                            bommelError={feeBommelError}
                        />
                    )}

                    {/* Color swatches */}
                    <div className="grid gap-1.5">
                        <Label>{t('bankAccounts.form.color')}</Label>
                        <div className="flex gap-2.5">
                            {ACCT_COLORS.map((c) => (
                                <button
                                    key={c}
                                    type="button"
                                    onClick={() => setValue('color', c)}
                                    className={cn(
                                        'w-9 h-9 rounded-xl transition-all',
                                        selectedColor === c ? 'ring-2 ring-offset-2 ring-gray-900 dark:ring-white scale-110' : 'opacity-80 hover:opacity-100'
                                    )}
                                    style={{ background: c }}
                                />
                            ))}
                        </div>
                    </div>

                    <DialogFooter className="pt-2">
                        <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={isLoading}>
                            {t('common.cancel')}
                        </Button>
                        <Button type="submit" disabled={isLoading}>
                            {isLoading ? <LoadingState size="sm" /> : isEdit ? t('common.save') : t('konten.form.create')}
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}
