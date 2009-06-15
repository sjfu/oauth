package Net::OAuth::UserAuthResponse;
use warnings;
use strict;
use base 'Net::OAuth::Response';
use Net::OAuth;

sub allow_extra_params {1}

if ($Net::OAuth::PROTOCOL_VERSION == Net::OAuth::PROTOCOL_VERSION_1_0A_COMPAT) {
    __PACKAGE__->add_optional_message_params(qw/verifier/);
}
elsif ($Net::OAuth::PROTOCOL_VERSION > Net::OAuth::PROTOCOL_VERSION_1_0A_COMPAT) {
    __PACKAGE__->add_required_message_params(qw/verifier/);
}

=head1 NAME

Net::OAuth::UserAuthResponse - An OAuth protocol response for an Access Token

=head1 SEE ALSO

L<Net::OAuth>, L<http://oauth.net>

=head1 AUTHOR

Keith Grennan, C<< <kgrennan at cpan.org> >>

=head1 COPYRIGHT & LICENSE

Copyright 2007 Keith Grennan, all rights reserved.

This program is free software; you can redistribute it and/or modify it
under the same terms as Perl itself.

=cut

1;