package vn.careermap.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** SePay bank-transfer settings; secrets and account details come from env vars. */
@Component
public class SePayConfig {

  private final String paymentProvider;
  private final String bank;
  private final String accountNumber;
  private final String accountHolder;
  private final String paymentCodePrefix;
  private final String memoPrefix;
  private final String webhookSecret;

  public SePayConfig(
      @Value("${payment.provider:vnpay}") String paymentProvider,
      @Value("${sepay.bank:}") String bank,
      @Value("${sepay.account-number:}") String accountNumber,
      @Value("${sepay.account-holder:}") String accountHolder,
      @Value("${sepay.payment-code-prefix:CM}") String paymentCodePrefix,
      @Value("${sepay.memo-prefix:}") String memoPrefix,
      @Value("${sepay.webhook-secret:}") String webhookSecret) {
    this.paymentProvider = clean(paymentProvider).toUpperCase();
    this.bank = clean(bank);
    this.accountNumber = clean(accountNumber);
    this.accountHolder = clean(accountHolder);
    this.paymentCodePrefix = clean(paymentCodePrefix).toUpperCase();
    this.memoPrefix = clean(memoPrefix);
    this.webhookSecret = clean(webhookSecret);
  }

  public static SePayConfig vnpayDefaults() {
    return new SePayConfig("VNPAY", "", "", "", "CM", "", "");
  }

  public String getPaymentProvider() {
    return paymentProvider;
  }

  public String getBank() {
    return bank;
  }

  public String getAccountNumber() {
    return accountNumber;
  }

  public String getAccountHolder() {
    return accountHolder;
  }

  public String getPaymentCodePrefix() {
    return paymentCodePrefix;
  }

  public String getMemoPrefix() {
    return memoPrefix;
  }

  public String getWebhookSecret() {
    return webhookSecret;
  }

  public boolean usesSePay() {
    return "SEPAY".equals(paymentProvider);
  }

  public boolean isSePayReady() {
    return !bank.isBlank()
        && !accountNumber.isBlank()
        && !accountHolder.isBlank()
        && paymentCodePrefix.length() >= 2
        && !webhookSecret.isBlank();
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
