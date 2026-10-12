package br.com.wodz.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Carga ou variação do movimento publicada pela organização, na ordem de exibição. */
public record MovementStandard(@NotBlank @Size(max = 80) String movement,
                               @NotBlank @Size(max = 60) String male,
                               @NotBlank @Size(max = 60) String female) {}
