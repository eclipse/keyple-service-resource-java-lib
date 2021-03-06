/* **************************************************************************************
 * Copyright (c) 2021 Calypso Networks Association https://calypsonet.org/
 *
 * See the NOTICE file(s) distributed with this work for additional information
 * regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ************************************************************************************** */
package org.eclipse.keyple.core.service.resource;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.calypsonet.terminal.reader.CardReader;
import org.calypsonet.terminal.reader.selection.spi.SmartCard;
import org.eclipse.keyple.core.service.Plugin;
import org.eclipse.keyple.core.service.SmartCardServiceProvider;
import org.eclipse.keyple.core.service.resource.spi.CardResourceProfileExtension;
import org.eclipse.keyple.core.service.resource.spi.ReaderConfiguratorSpi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * (package-private)<br>
 * Manager of a reader associated to a "regular" plugin.
 *
 * <p>It contains all associated created card resources and manages concurrent access to the
 * reader's card resources so that only one card resource can be used at a time.
 *
 * @since 2.0.0
 */
final class ReaderManagerAdapter {

  private static final Logger logger = LoggerFactory.getLogger(ReaderManagerAdapter.class);

  /** The associated reader */
  private final CardReader reader;

  /** The associated plugin */
  private final Plugin plugin;

  /** Collection of all created card resources. */
  private final Set<CardResource> cardResources;

  /** The reader configurator, not null if the monitoring is activated for the associated reader. */
  private final ReaderConfiguratorSpi readerConfiguratorSpi;

  /** The max usage duration of a card resource before it will be automatically release. */
  private final int usageTimeoutMillis;

  /**
   * Indicates the time after which the reader will be automatically unlocked if a new lock is
   * requested.
   */
  private long lockMaxTimeMillis;

  /** Current selected card resource. */
  private CardResource selectedCardResource;

  /** Indicates if a card resource is actually in use. */
  private volatile boolean isBusy;

  /** Indicates if the associated reader is accepted by at least one card profile manager. */
  private volatile boolean isActive;

  /**
   * (package-private)<br>
   * Creates a new reader manager not active by default.
   *
   * @param reader The associated reader.
   * @param plugin The associated plugin.
   * @param readerConfiguratorSpi The reader configurator to use.
   * @param usageTimeoutMillis The max usage duration of a card resource before it will be
   *     automatically release.
   * @since 2.0.0
   */
  ReaderManagerAdapter(
      CardReader reader,
      Plugin plugin,
      ReaderConfiguratorSpi readerConfiguratorSpi,
      int usageTimeoutMillis) {
    this.reader = reader;
    this.plugin = plugin;
    this.readerConfiguratorSpi = readerConfiguratorSpi;
    this.usageTimeoutMillis = usageTimeoutMillis;
    this.cardResources = Collections.newSetFromMap(new ConcurrentHashMap<CardResource, Boolean>());
    this.selectedCardResource = null;
    this.isBusy = false;
    this.isActive = false;
  }

  /**
   * (package-private)<br>
   * Gets the associated reader.
   *
   * @return A not null reference.
   * @since 2.0.0
   */
  CardReader getReader() {
    return reader;
  }

  /**
   * (package-private)<br>
   * Gets the associated plugin.
   *
   * @return A not null reference.
   * @since 2.0.0
   */
  Plugin getPlugin() {
    return plugin;
  }

  /**
   * (package-private)<br>
   * Gets a view of the current created card resources.
   *
   * @return An empty collection if there's no card resources.
   * @since 2.0.0
   */
  Set<CardResource> getCardResources() {
    return cardResources;
  }

  /**
   * (package-private)<br>
   * Indicates if the associated reader is accepted by at least one card profile manager.
   *
   * @return True if the reader manager is active.
   * @since 2.0.0
   */
  boolean isActive() {
    return isActive;
  }

  /**
   * (package-private)<br>
   * Activates the reader manager and setup the reader if needed.
   *
   * @since 2.0.0
   */
  void activate() {
    if (!isActive) {
      readerConfiguratorSpi.setupReader(reader);
    }
    isActive = true;
  }

  /**
   * (package-private)<br>
   * Gets a new or an existing card resource if the current inserted card matches with the provided
   * card resource profile extension.
   *
   * <p>If the card matches, then updates the current selected card resource.
   *
   * <p>In any case, invoking this method unlocks the reader due to the use of the card selection
   * manager by the extension during the match process.
   *
   * @param extension The card resource profile extension to use for matching.
   * @return Null if the inserted card does not match with the provided profile extension.
   * @since 2.0.0
   */
  CardResource matches(CardResourceProfileExtension extension) {
    CardResource cardResource = null;
    SmartCard smartCard =
        extension.matches(
            reader, SmartCardServiceProvider.getService().createCardSelectionManager());
    if (smartCard != null) {
      cardResource = getOrCreateCardResource(smartCard);
      selectedCardResource = cardResource;
    }
    unlock();
    return cardResource;
  }

  /**
   * (package-private)<br>
   * Tries to lock the provided card resource if the reader is not busy.
   *
   * <p>If the provided card resource is not the current selected one, then tries to select it using
   * the provided card resource profile extension.
   *
   * @param cardResource The card resource to lock.
   * @param extension The card resource profile extension to use in case if a new selection is
   *     needed.
   * @return True if the card resource is locked.
   * @throws IllegalStateException If a new selection has been made and the current card does not
   *     match the provided profile extension or is not the same smart card than the provided one.
   * @since 2.0.0
   */
  boolean lock(CardResource cardResource, CardResourceProfileExtension extension) {
    if (isBusy) {
      if (System.currentTimeMillis() < lockMaxTimeMillis) {
        return false;
      }
      logger.warn(
          "Reader '{}' automatically unlocked due to a usage duration over than {} milliseconds.",
          reader.getName(),
          usageTimeoutMillis);
    }
    if (selectedCardResource != cardResource) {
      SmartCard smartCard =
          extension.matches(
              reader, SmartCardServiceProvider.getService().createCardSelectionManager());
      if (!areEquals(cardResource.getSmartCard(), smartCard)) {
        selectedCardResource = null;
        throw new IllegalStateException(
            "No card is inserted or its profile does not match the associated data.");
      }
      selectedCardResource = cardResource;
    }
    lockMaxTimeMillis = System.currentTimeMillis() + usageTimeoutMillis;
    isBusy = true;
    return true;
  }

  /**
   * (package-private)<br>
   * Free the reader.
   *
   * @since 2.0.0
   */
  void unlock() {
    isBusy = false;
  }

  /**
   * (package-private)<br>
   * Removes the provided card resource.
   *
   * @param cardResource The card resource to remove.
   * @since 2.0.0
   */
  void removeCardResource(CardResource cardResource) {
    cardResources.remove(cardResource);
    if (selectedCardResource == cardResource) {
      selectedCardResource = null;
    }
  }

  /**
   * (private)<br>
   * Gets an existing card resource having the same smart card than the provided one, or creates a
   * new one if not.
   *
   * @param smartCard The associated smart card.
   * @return A not null reference.
   */
  private CardResource getOrCreateCardResource(SmartCard smartCard) {

    // Check if an identical card resource is already created.
    for (CardResource cardResource : cardResources) {
      if (areEquals(cardResource.getSmartCard(), smartCard)) {
        return cardResource;
      }
    }

    // If none, then create a new one.
    CardResource cardResource = new CardResource(reader, smartCard);
    cardResources.add(cardResource);
    return cardResource;
  }

  /**
   * (private)<br>
   * Checks if the provided Smart Cards are identical.
   *
   * @param s1 Smart Card 1
   * @param s2 Smart Card 2
   * @return True if they are identical.
   */
  private boolean areEquals(SmartCard s1, SmartCard s2) {

    if (s1 == s2) {
      return true;
    }

    if (s1 == null || s2 == null) {
      return false;
    }

    boolean hasSamePowerOnData =
        (s1.getPowerOnData() == null && s2.getPowerOnData() == null)
            || (s1.getPowerOnData() != null && s1.getPowerOnData().equals(s2.getPowerOnData()));

    boolean hasSameFci =
        Arrays.equals(s1.getSelectApplicationResponse(), s2.getSelectApplicationResponse());

    return hasSamePowerOnData && hasSameFci;
  }
}
